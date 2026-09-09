package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.IncidentAutomationProperties;
import org.example.entity.IncidentEventEntity;
import org.example.repository.IncidentEventRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

import org.example.dto.IncidentWebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "aiops.legacy-incident.enabled", havingValue = "true")
public class IncidentAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(IncidentAutomationService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_RETRY = "RETRY";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";
    private static final List<String> EXECUTING_STATUSES = Arrays.asList(STATUS_PENDING, STATUS_RETRY);
    private static final String AI_OPS_SYSTEM_PROMPT_PREFIX = "你是企业级 SRE 自动运维机器人，负责告警自动 triage 与闭环建议。请基于告警证据和工具返回内容逐步输出，可分层次给出：1) 告警拆解；2) 根因分析；3) 已执行建议；4) 验证建议；5) 是否可自动化执行。\n";

    private final IncidentEventRepository incidentEventRepository;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;
    private final AiOpsService aiOpsService;
    private final IncidentAutomationProperties properties;
    private final Optional<ToolCallbackProvider> tools;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Long> runningEvents = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public IncidentAutomationService(
            IncidentEventRepository incidentEventRepository,
            ObjectMapper objectMapper,
            ChatService chatService,
            AiOpsService aiOpsService,
            IncidentAutomationProperties properties,
            Optional<ToolCallbackProvider> tools
    ) {
        this.incidentEventRepository = incidentEventRepository;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
        this.aiOpsService = aiOpsService;
        this.properties = properties;
        this.tools = tools;
    }

    public List<IncidentEventEntity> receiveWebhook(Map<String, Object> payload, String ownerId) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Incident 自动化未开启");
        }
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("事件内容不能为空");
        }
        String normalizedOwner = normalizeOwner(ownerId);
        List<IncidentWebhookRequest> candidates = extractIncidentRequests(payload);
        if (candidates.isEmpty()) {
            IncidentWebhookRequest fallback = fromMap(payload);
            candidates.add(fallback);
        }
        List<IncidentEventEntity> created = new ArrayList<>();
        long now = Instant.now().toEpochMilli();
        for (IncidentWebhookRequest candidate : candidates) {
            Map<String, Object> eventPayload = candidate.getRawPayload();
            if (eventPayload == null || eventPayload.isEmpty()) {
                eventPayload = payload;
            }
            IncidentEventEntity event = buildEventFromPayload(candidate, eventPayload, normalizedOwner, now);
            if (event == null) {
                continue;
            }
            if (properties.isAutoRunEnabled()) {
                triggerRunAsync(event.getEventId());
            }
            created.add(event);
        }
        return created;
    }

    public List<IncidentEventEntity> listEvents(@Nullable String ownerId, @Nullable String status, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        PageRequest page = PageRequest.of(0, safeLimit);
        String normalizedOwner = normalizeOwner(ownerId);
        if (status == null || status.isBlank()) {
            return incidentEventRepository.findByOwnerIdOrderByCreatedAtDesc(normalizedOwner, page);
        }
        return incidentEventRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                normalizedOwner,
                status.trim().toUpperCase(),
                page
        );
    }

    public Optional<IncidentEventEntity> getEvent(@Nullable String ownerId, String eventId) {
        String normalizedOwner = normalizeOwner(ownerId);
        return incidentEventRepository.findByEventId(eventId)
                .filter(event -> normalizedOwner.equals(event.getOwnerId()));
    }

    public void triggerRunAsync(String eventId) {
        if (!properties.isEnabled() || !properties.isAutoRunEnabled()) {
            return;
        }
        executor.execute(() -> processEventAsync(eventId, true));
    }

    public void triggerRunAsyncByOwner(String ownerId, String eventId) {
        if (!properties.isEnabled() || !properties.isAutoRunEnabled()) {
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        String normalizedOwner = normalizeOwner(ownerId);
        Optional<IncidentEventEntity> eventOpt = incidentEventRepository.findByEventId(eventId);
        if (eventOpt.isEmpty() || !normalizedOwner.equals(eventOpt.get().getOwnerId())) {
            return;
        }
        triggerRunAsync(eventId);
    }

    @Scheduled(fixedDelayString = "${incident.automation.poll-interval-ms:8000}")
    public void pollPendingEvents() {
        if (!properties.isEnabled() || !properties.isAutoRunEnabled()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        List<IncidentEventEntity> events = incidentEventRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                EXECUTING_STATUSES,
                now,
                PageRequest.of(0, properties.getBatchSize())
        );
        for (IncidentEventEntity event : events) {
            triggerRunAsync(event.getEventId());
        }
    }

    private void processEventAsync(String eventId, boolean immediate) {
        if (!properties.isEnabled()) {
            return;
        }
        Long running = runningEvents.get(eventId);
        if (running != null) {
            return;
        }
        runningEvents.put(eventId, Instant.now().toEpochMilli());
        try {
            runOnce(eventId, immediate);
        } finally {
            runningEvents.remove(eventId);
        }
    }

    private void runOnce(String eventId, boolean immediate) {
        Optional<IncidentEventEntity> eventOptional = incidentEventRepository.findByEventId(eventId);
        if (eventOptional.isEmpty()) {
            return;
        }
        IncidentEventEntity event = eventOptional.get();
        String status = normalizeStatus(event.getStatus());
        if (STATUS_DONE.equals(status) && !immediate) {
            return;
        }
        if (!(STATUS_PENDING.equals(status) || STATUS_RETRY.equals(status) || STATUS_RUNNING.equals(status))) {
            return;
        }

        long now = Instant.now().toEpochMilli();
        event.setStatus(STATUS_RUNNING);
        event.setLastAttemptAt(now);
        event.setAttempts(Math.max(0, event.getAttempts()) + 1);
        event.setUpdatedAt(now);
        incidentEventRepository.save(event);

        try {
            String prompt = buildPrompt(event);
            String finalReport = runAiOps(prompt);
            if (finalReport == null || finalReport.isBlank()) {
                throw new IllegalStateException("AI 运维引擎未返回可解析的告警报告");
            }
            event.setStatus(STATUS_DONE);
            event.setFinalReport(truncate(finalReport, 30000));
            event.setLastError(null);
            event.setNextAttemptAt(null);
            event.setResolvedAt(Instant.now().toEpochMilli());
            event.setUpdatedAt(Instant.now().toEpochMilli());
            incidentEventRepository.save(event);
            logger.info("告警事件处理完成 - EventId: {}, 标题: {}", event.getEventId(), event.getEventTitle());
            maybeNotify(event, true);
            return;
        } catch (Exception ex) {
            onFailure(event, ex);
        }
    }

    private String runAiOps(String prompt) throws GraphRunnerException {
        if (prompt == null || prompt.isBlank()) {
            prompt = "请基于默认告警处理规则执行一次 incident triage。";
        }
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.2)
                        .withMaxToken(6000)
                        .withTopP(0.9)
                        .build())
                .build();

        ToolCallback[] toolCallbacks = tools
                .map(ToolCallbackProvider::getToolCallbacks)
                .orElseGet(() -> new ToolCallback[0]);

        Optional<OverAllState> overAllState = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);
        return overAllState.flatMap(aiOpsService::extractFinalReport).orElse(null);
    }

    private void onFailure(IncidentEventEntity event, Exception ex) {
        event.setUpdatedAt(Instant.now().toEpochMilli());
        event.setLastError(truncate(ex.getMessage(), 4000));
        event.setFinalReport(null);
        event.setResolvedAt(null);
        if (event.getAttempts() >= properties.getMaxAttempts()) {
            event.setStatus(STATUS_FAILED);
            event.setNextAttemptAt(null);
            event.setUpdatedAt(Instant.now().toEpochMilli());
            incidentEventRepository.save(event);
            logger.error("告警事件处理失败（已达到最大重试次数） - EventId: {}, Error: {}", event.getEventId(), ex.getMessage());
            maybeNotify(event, false);
            return;
        }
        long delayMs = computeRetryDelayMs(event.getAttempts());
        event.setStatus(STATUS_RETRY);
        event.setNextAttemptAt(Instant.now().toEpochMilli() + delayMs);
        event.setUpdatedAt(Instant.now().toEpochMilli());
        incidentEventRepository.save(event);
        logger.warn("告警事件处理失败，已排队重试 - EventId: {}, attempts={}/{}, 下一次: {}ms",
                event.getEventId(), event.getAttempts(), properties.getMaxAttempts(), delayMs);
    }

    private void maybeNotify(IncidentEventEntity event, boolean success) {
        if (success && !properties.isNotifyOnResolved()) {
            return;
        }
        if (!success && !properties.isNotifyOnUnresolved()) {
            return;
        }
        if (!properties.shouldNotify(event.getSeverity())) {
            return;
        }
        sendWebhookNotify(event);
        sendWechatNotify(event, success);
    }

    private void sendWebhookNotify(IncidentEventEntity event) {
        if (properties.getNotifyWebhookUrl() == null || properties.getNotifyWebhookUrl().isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = buildCommonNotificationPayload(event);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getNotifyWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("已发送通知 Webhook - EventId: {}, httpStatus: {}", event.getEventId(), response.statusCode());
        } catch (Exception e) {
            logger.error("通知 Webhook 失败 - EventId: {}", event.getEventId(), e);
        }
    }

    private void sendWechatNotify(IncidentEventEntity event, boolean success) {
        if (!properties.isWechatNotifyEnabled() || properties.getWechatWebhookUrl() == null || properties.getWechatWebhookUrl().isBlank()) {
            return;
        }
        try {
            String markdown = buildWechatMarkdown(event, success);
            if (sendWechatPayload(buildWechatMarkdownPayload(markdown))) {
                logger.info("已发送企业微信 markdown 告警通知 - EventId: {}", event.getEventId());
                return;
            }
            logger.warn("企业微信 markdown 发送失败，自动降级 Text - EventId: {}", event.getEventId());
            sendWechatTextNotify(event, success);
        } catch (Exception e) {
            logger.error("企业微信告警通知发送异常，准备降级 - EventId: {}", event.getEventId(), e);
            sendWechatTextNotify(event, success);
        }
    }

    private void sendWechatTextNotify(IncidentEventEntity event, boolean success) {
        try {
            String text = buildWechatText(event, success);
            if (sendWechatPayload(buildWechatTextPayload(text))) {
                logger.info("已发送企业微信 text 告警通知 - EventId: {}", event.getEventId());
            } else {
                logger.error("企业微信 text 告警通知失败 - EventId: {}", event.getEventId());
            }
        } catch (Exception ex) {
            logger.error("企业微信 text 告警通知异常 - EventId: {}", event.getEventId(), ex);
        }
    }

    private boolean sendWechatPayload(Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getWechatWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return isWechatSuccess(response.statusCode(), response.body());
        } catch (Exception ex) {
            logger.error("企业微信请求失败 - Webhook: {}", properties.getWechatWebhookUrl(), ex);
            return false;
        }
    }

    private boolean isWechatSuccess(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            return false;
        }
        if (body == null || body.isBlank()) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            Object errCode = parsed.get("errcode");
            if (errCode == null) {
                return true;
            }
            String code = errCode.toString();
            return "0".equals(code) || "0.0".equals(code);
        } catch (Exception ex) {
            return true;
        }
    }

    private Map<String, Object> buildCommonNotificationPayload(IncidentEventEntity event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("externalEventId", event.getExternalEventId());
        payload.put("status", event.getStatus());
        payload.put("severity", event.getSeverity());
        payload.put("serviceName", event.getServiceName());
        payload.put("source", event.getSource());
        payload.put("summary", event.getSummary());
        payload.put("eventTitle", event.getEventTitle());
        payload.put("updatedAt", event.getUpdatedAt());
        payload.put("finalReport", event.getFinalReport());
        payload.put("lastError", event.getLastError());
        return payload;
    }

    private Map<String, Object> buildWechatMarkdownPayload(String markdown) {
        Map<String, Object> markdownPayload = new LinkedHashMap<>();
        markdownPayload.put("content", markdown + buildWechatMentionText());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdownPayload);
        payload.put("safe", 0);
        payload.put("enable_id_trans", 0);
        payload.put("enable_duplicate_check", 0);
        payload.put("duplicate_check_interval", 1800);
        return payload;
    }

    private Map<String, Object> buildWechatTextPayload(String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> textPayload = new LinkedHashMap<>();
        textPayload.put("content", content + buildWechatMentionText());
        if (!properties.getWechatMentionedUsers().isEmpty()) {
            textPayload.put("mentioned_list", properties.getWechatMentionedUsers());
        }
        if (!properties.getWechatMentionedMobiles().isEmpty()) {
            textPayload.put("mentioned_mobile_list", properties.getWechatMentionedMobiles());
        }
        payload.put("msgtype", "text");
        payload.put("text", textPayload);
        return payload;
    }

    private String buildWechatMentionText() {
        if (properties.getWechatMentionedUsers().isEmpty() && properties.getWechatMentionedMobiles().isEmpty()) {
            return "";
        }
        StringBuilder mentions = new StringBuilder();
        for (String user : properties.getWechatMentionedUsers()) {
            mentions.append("\n@").append(user);
        }
        for (String mobile : properties.getWechatMentionedMobiles()) {
            mentions.append("\n@").append(mobile);
        }
        return mentions.toString();
    }

    private String buildWechatText(IncidentEventEntity event, boolean success) {
        String status = success ? "✅ 处理完成（可人工复核）" : "⚠️ 处理异常（请尽快介入）";
        StringBuilder content = new StringBuilder();
        content.append("【AIOps 告警闭环通知】\n");
        content.append("状态：").append(status).append("\n");
        content.append("严重等级：").append(mapWechatSeverityLabel(event.getSeverity())).append("\n");
        content.append("事件ID：").append(defaultText(event.getEventId())).append("\n");
        content.append("来源：").append(defaultText(event.getSource())).append("\n");
        content.append("服务：").append(defaultText(event.getServiceName())).append("\n");
        content.append("摘要：").append(defaultText(event.getSummary())).append("\n");
        if (event.getFinalReport() != null && !event.getFinalReport().isBlank()) {
            content.append("AI要点：").append(truncate(event.getFinalReport(), 800)).append("\n");
        }
        if (event.getLastError() != null && !event.getLastError().isBlank()) {
            content.append("异常：").append(truncate(event.getLastError(), 600)).append("\n");
        }
        content.append("建议：结合监控/日志/发布记录复核。");
        return content.toString();
    }

    private String buildWechatMarkdown(IncidentEventEntity event, boolean success) {
        String severityColor = mapWechatSeverityColor(event.getSeverity());
        String status = success ? "✅ 处理完成（可人工复核）" : "⚠️ 处理异常（请尽快介入）";
        StringBuilder content = new StringBuilder();
        content.append("### <font color=\"").append(severityColor).append("\">AIOps 告警闭环通知</font>\n\n");
        content.append("> <font color=\"").append(severityColor).append("\">状态</font>：").append(status).append("\n\n");
        content.append("> <font color=\"comment\">事件ID</font>：").append(defaultText(event.getEventId())).append("\n");
        content.append("> <font color=\"comment\">来源</font>：").append(defaultText(event.getSource())).append("\n");
        content.append("> <font color=\"comment\">服务</font>：").append(defaultText(event.getServiceName())).append("\n");
        content.append("> <font color=\"comment\">级别</font>：").append(defaultText(event.getSeverity())).append("\n");
        content.append("> <font color=\"comment\">摘要</font>：").append(defaultText(event.getSummary())).append("\n\n");
        if (event.getFinalReport() != null && !event.getFinalReport().isBlank()) {
            content.append("#### AI 分析要点（摘要）\n");
            content.append("> ").append(truncate(event.getFinalReport(), 1800)).append("\n\n");
        }
        if (event.getLastError() != null && !event.getLastError().isBlank()) {
            content.append("#### 最近失败原因\n");
            content.append("> ").append(truncate(event.getLastError(), 900)).append("\n\n");
        }
        content.append("> 建议：请结合监控、日志与发布记录进行二次确认。");
        return content.toString();
    }

    private String mapWechatSeverityColor(String severity) {
        String normalized = normalizeSeverity(severity);
        return switch (normalized) {
            case "P0" -> "red";
            case "P1" -> "warning";
            case "P2" -> "comment";
            case "P3", "P4" -> "info";
            default -> "comment";
        };
    }

    private String mapWechatSeverityLabel(String severity) {
        String normalized = normalizeSeverity(severity);
        return switch (normalized) {
            case "P0" -> "P0（红）";
            case "P1" -> "P1（橙）";
            case "P2" -> "P2（黄）";
            case "P3", "P4" -> "P3/P4（蓝）";
            default -> "UNKNOWN";
        };
    }

    private IncidentEventEntity buildEventFromPayload(IncidentWebhookRequest request, Map<String, Object> rawPayload,
                                                     String ownerId, long now) {
        String eventFingerprint = normalizeText(
                request.getEventId(),
                request.getSummary(),
                request.getDescription(),
                request.getTitle()
        );
        String source = firstNonBlank(
                request.getSource(),
                "webhook"
        );
        String service = firstNonBlank(
                request.getService(),
                request.getServiceName(),
                mapTextValue(request.getLabels(), "service"),
                mapTextValue(request.getLabels(), "service_name"),
                mapTextValue(request.getLabels(), "app"),
                "unknown_service"
        );
        String severity = normalizeSeverity(request.getSeverity());
        String summary = firstNonBlank(
                request.getSummary(),
                request.getDescription(),
                request.getTitle(),
                mapTextValue(request.getAnnotations(), "summary"),
                source + " 告警事件"
        );
        String title = firstNonBlank(request.getTitle(), request.getSummary(), "未命名告警");

        if (eventFingerprint != null && !eventFingerprint.isBlank() && request.getEventId() != null) {
            Optional<IncidentEventEntity> existing = incidentEventRepository.findFirstByOwnerIdAndExternalEventIdOrderByCreatedAtDesc(
                    ownerId, request.getEventId().trim()
            );
            if (existing.isPresent()) {
                IncidentEventEntity previous = existing.get();
                if (!STATUS_DONE.equals(normalizeStatus(previous.getStatus()))) {
                    previous.setStatus(STATUS_PENDING);
                    previous.setAttempts(0);
                    previous.setPayloadJson(serializePayload(rawPayload, 12000));
                    previous.setUpdatedAt(now);
                    previous.setNextAttemptAt(now);
                    previous.setLastAttemptAt(null);
                    previous.setLastError(null);
                    previous.setFinalReport(null);
                    previous.setSeverity(severity);
                    previous.setSource(source);
                    previous.setServiceName(service);
                    previous.setEventTitle(title);
                    previous.setSummary(summary);
                    return incidentEventRepository.save(previous);
                }
            }
        }

        String eventId = UUID.randomUUID().toString();
        IncidentEventEntity event = new IncidentEventEntity();
        event.setEventId(eventId);
        event.setExternalEventId(normalizeEventId(request.getEventId()));
        event.setOwnerId(ownerId);
        event.setSource(source);
        event.setSeverity(severity);
        event.setServiceName(service);
        event.setEventTitle(title);
        event.setSummary(summary);
        event.setStatus(STATUS_PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        event.setPayloadJson(serializePayload(rawPayload, 12000));
        return incidentEventRepository.save(event);
    }

    private List<IncidentWebhookRequest> extractIncidentRequests(Map<String, Object> payload) {
        Object alertsObj = payload.get("alerts");
        if (!(alertsObj instanceof List<?> alerts)) {
            return new ArrayList<>(0);
        }
        List<IncidentWebhookRequest> requests = new ArrayList<>();
        for (Object item : alerts) {
            if (item == null) {
                continue;
            }
            if (item instanceof Map<?, ?> itemMap) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) itemMap;
                    requests.add(fromMap(typed));
                } catch (Exception e) {
                    logger.warn("转换告警明细失败 - payloadItem: {}", item, e);
                }
            }
        }
        return requests;
    }

    private IncidentWebhookRequest fromMap(Map<String, Object> map) {
        IncidentWebhookRequest request = objectMapper.convertValue(map, IncidentWebhookRequest.class);
        if (request == null) {
            request = new IncidentWebhookRequest();
        }
        request.setRawPayload(map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map));
        return request;
    }

    private String buildPrompt(IncidentEventEntity event) {
        StringBuilder builder = new StringBuilder();
        builder.append(AI_OPS_SYSTEM_PROMPT_PREFIX);
        builder.append("请立刻读取当前告警事实并给出告警根因排查建议。不得编造数据。\n\n");
        builder.append("事件ID: ").append(event.getEventId()).append("\n");
        builder.append("外部事件标识: ").append(defaultText(event.getExternalEventId())).append("\n");
        builder.append("来源: ").append(defaultText(event.getSource())).append("\n");
        builder.append("服务: ").append(defaultText(event.getServiceName())).append("\n");
        builder.append("级别: ").append(defaultText(event.getSeverity())).append("\n");
        builder.append("标题: ").append(defaultText(event.getEventTitle())).append("\n");
        builder.append("摘要: ").append(defaultText(event.getSummary())).append("\n");
        builder.append("原始事件: ").append(defaultText(event.getPayloadJson())).append("\n");
        builder.append("请输出：\n");
        builder.append("1) 告警 triage 清单（优先级/影响范围/责任域）\n");
        builder.append("2) 可执行验证步骤清单（需提到工具或指标）\n");
        builder.append("3) 是否建议自动执行：是/否 + 原因\n");
        builder.append("4) 若无法完成，给出明确人工交接清单\n");
        return builder.toString();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_PENDING;
        }
        return status.trim().toUpperCase();
    }

    private String normalizeOwner(String ownerId) {
        return ownerId == null || ownerId.isBlank() ? "public" : ownerId;
    }

    private String normalizeEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        String trimmed = eventId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = severity.trim().toUpperCase();
        if (!normalized.startsWith("P")) {
            return normalized;
        }
        if ("P0".equals(normalized) || "P1".equals(normalized) || "P2".equals(normalized) || "P3".equals(normalized) || "P4".equals(normalized)) {
            return normalized;
        }
        return "UNKNOWN";
    }

    private long computeRetryDelayMs(int attempt) {
        long exp = Math.max(0, attempt - 1L);
        long maxDelay = properties.getRetryMaxDelaySeconds();
        long base = properties.getRetryBaseDelaySeconds();
        long delay = Math.min(maxDelay, base * (1L << Math.min(10, (int) exp)));
        return Math.max(1_000L, delay * 1_000L);
    }

    private String normalizeText(String... items) {
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                return item.trim();
            }
        }
        return null;
    }

    private String firstNonBlank(String... items) {
        return normalizeText(items);
    }

    private String mapTextValue(Map<String, Object> map, String key) {
        if (map == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.trim().isEmpty() ? null : text.trim();
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String safe = text.strip();
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, Math.max(1, maxLength - 1)) + "...";
    }

    private String serializePayload(Map<String, Object> payload, int maxLength) {
        if (payload == null) {
            return "{}";
        }
        try {
            return truncate(objectMapper.writeValueAsString(payload), maxLength);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
