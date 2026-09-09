package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维策略与交接工具。
 * 在 AI Ops 工作流中用于生成分级建议、依赖风险评估和交接包。
 */
@Component
public class IncidentOperationsTools {

    private static final Logger logger = LoggerFactory.getLogger(IncidentOperationsTools.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Build a clear incident triage package from summary and impacted services. " +
            "The output includes priority score and suggested order to act.")
    public String buildIncidentTriagePackage(
            @ToolParam(description = "Incident summary, e.g. HighCPU on payment-service") String incidentSummary,
            @ToolParam(description = "Observed impact services, comma separated, e.g. payment-service,order-service") String impactedServices,
            @ToolParam(description = "Business criticality, one of high/medium/low") String criticality,
            @ToolParam(description = "Current alert count observed, e.g. 3") Integer activeAlertCount) {
        int alertCount = activeAlertCount == null ? 0 : activeAlertCount;
        String[] serviceArray = impactedServices == null ? new String[0] : impactedServices.split(",");

        int priorityScore = 40;
        String normalizedCriticality = normalize(criticality);
        if ("high".equalsIgnoreCase(normalizedCriticality)) {
            priorityScore += 40;
        } else if ("medium".equalsIgnoreCase(normalizedCriticality)) {
            priorityScore += 20;
        }
        priorityScore += Math.min(alertCount * 4, 30);
        if (serviceArray.length > 3) {
            priorityScore += 15;
        }
        priorityScore = Math.min(priorityScore, 100);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("incidentSummary", normalize(incidentSummary));
        output.put("priorityScore", priorityScore);
        output.put("priorityLevel", priorityScore >= 80 ? "P0" : priorityScore >= 60 ? "P1" : priorityScore >= 40 ? "P2" : "P3");
        output.put("impactedServices", compactServices(serviceArray));
        output.put("recommendedFirstAction", firstAction(priorityScore, normalizedCriticality));
        output.put("actions", recommendationItems(priorityScore));
        output.put("timeboxedSLO", priorityScore >= 80 ? "15min" : "30min");
        return writeValueQuietly(output);
    }

    @Tool(description = "Generate dependency risk matrix and escalation sequence for incident handling.")
    public String buildDependencyRiskMatrix(
            @ToolParam(description = "Primary service name") String primaryService,
            @ToolParam(description = "Observed dependency clues, comma separated") String dependencySignals,
            @ToolParam(description = "Whether there is active deployment, true/false") Boolean activeDeployment,
            @ToolParam(description = "Estimated recovery window minutes") Integer recoveryWindowMinutes) {
        String[] signals = dependencySignals == null ? new String[0] : dependencySignals.split(",");
        int recoveryWindow = recoveryWindowMinutes == null ? 0 : recoveryWindowMinutes;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("primaryService", normalize(primaryService));
        output.put("riskSignals", compactServices(signals));
        output.put("activeDeployment", Boolean.TRUE.equals(activeDeployment));
        output.put("recoveryWindowMinutes", recoveryWindow);

        List<String> risks = new ArrayList<>();
        if (signals.length == 0) {
            risks.add("未提供足够依赖线索，建议先拉取日志/告警确认上游下游关系");
        } else {
            risks.add("检测到多个依赖链索引，优先排查上游限流与下游数据库连接");
            risks.add("优先冻结可能引发雪崩的异步任务重试行为");
        }
        if (Boolean.TRUE.equals(activeDeployment)) {
            risks.add("存在正在发布中的版本，先暂停推流/灰度扩展，避免回滚窗口扩大影响");
        }
        output.put("riskItems", risks);

        output.put("escalationSequence", List.of(
                "第一梯队: SRE on-call + 1 名应用负责人",
                "第二梯队: 平台/中间件负责人",
                "第三梯队: 架构与 DBA（若涉及数据库/缓存）"
        ));
        output.put("nextChecks", List.of(
                "1) 再次验证关键告警的最新状态",
                "2) 抓取核心链路日志（应用+中间件+数据库）",
                "3) 再确认回滚开关与镜像可用性"
        ));
        return writeValueQuietly(output);
    }

    @Tool(description = "Generate operator handoff package for post-incident communication and verification tracking.")
    public String generateOperatorHandoff(@ToolParam(description = "Incident ID") String incidentId,
                                        @ToolParam(description = "负责人") String owner,
                                        @ToolParam(description = "一句话结论") String conclusion,
                                        @ToolParam(description = "下一步动作（可换行）") String nextActions) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("incidentId", normalize(incidentId));
        output.put("owner", normalize(owner));
        output.put("conclusion", normalize(conclusion));
        output.put("nextActions", nextActions == null ? "" : nextActions.trim());
        output.put("handoffTemplate", "\n【告警标题】[自动化事件记录]\n【结论】" + normalize(conclusion)
                + "\n【当前风险】请保持关注并在 15 分钟内复核指标\n【责任人】" + normalize(owner));
        output.put("verificationOwner", normalize(owner));
        output.put("nextVerificationTime", "15分钟后");
        return writeValueQuietly(output);
    }

    @Tool(description = "Build a concrete recovery execution plan for high-risk incidents, including canary, validation gates and rollback conditions.")
    public String buildRecoveryExecutionPlan(
            @ToolParam(description = "Incident ID") String incidentId,
            @ToolParam(description = "Main affected service") String primaryService,
            @ToolParam(description = "Affected services, comma separated") String affectedServices,
            @ToolParam(description = "Planned change window minutes") Integer changeWindowMinutes,
            @ToolParam(description = "Preferred mitigation mode, e.g. canary|batch|full") String mitigationMode,
            @ToolParam(description = "Rollback threshold value") Integer rollbackThreshold,
            @ToolParam(description = "负责人") String owner) {

        int window = changeWindowMinutes == null ? 0 : changeWindowMinutes;
        int serviceCount = splitList(affectedServices).size();
        int threshold = rollbackThreshold == null ? 5 : Math.max(1, rollbackThreshold);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("incidentId", normalize(incidentId));
        output.put("primaryService", normalize(primaryService));
        output.put("affectedServices", splitList(affectedServices));
        output.put("mitigationMode", normalize(mitigationMode));
        output.put("windowMinutes", window);
        output.put("rollbackThreshold", threshold);

        output.put("phase", List.of(
                "第一阶段：隔离高风险流量（限流或熔断）",
                "第二阶段：按分组逐步恢复，持续验证 SLI 与错误率",
                "第三阶段：通过验证后扩大全量，若触发阈值则立即回退"
        ));

        int totalWindow = Math.max(window, 15);
        output.put("recommendedIntervalMinutes", Math.max(2, totalWindow / Math.max(serviceCount, 1)));
        output.put("owner", normalize(owner));
        output.put("riskSignal", serviceCount >= 4 ? "CROSS_DOMAIN_CHAIN" : "INTRA_DOMAIN_CHAIN");
        output.put("checks", List.of(
                "逐步观察 5xx、错误率、P99、队列深度是否持续下降",
                "告警是否仍有新增，尤其是调用超时与连接池指标",
                "变更回退链路（镜像/配置）是否随时可用"
        ));
        output.put("rollbackRules", List.of(
                "若任意分钟内 5xx 较上一个窗口上升超过 300%，立即触发回滚",
                "若服务异常恢复时间超过窗口上限，进入人工兜底",
                "每轮变更必须有 on-call 与业务方同步确认"
        ));
        return writeValueQuietly(output);
    }

    private java.util.List<String> splitList(String services) {
        if (services == null || services.isBlank()) {
            return List.of();
        }
        return List.of(services.split(","))
                .stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<String> compactServices(String[] services) {
        List<String> result = new ArrayList<>();
        for (String service : services) {
            String item = normalize(service);
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    private String firstAction(int priorityScore, String criticality) {
        if (priorityScore >= 80 && "high".equalsIgnoreCase(criticality)) {
            return "立即执行告警封禁与流量隔离，先止损再深挖";
        }
        if (priorityScore >= 60) {
            return "限制影响范围并行采集日志与关键指标，确认回退可行性";
        }
        return "先聚焦根因候选项，形成单点验证动作";
    }

    private List<String> recommendationItems(int priorityScore) {
        if (priorityScore >= 80) {
            return List.of(
                    "启用高优先级响应模式，拉起双人值守",
                    "优先处理最核心用户路径",
                    "严格限定变更与回滚动作次数");
        }
        if (priorityScore >= 60) {
            return List.of(
                    "缩小影响面，冻结可疑重试链路",
                    "按 2-3 分钟步长验证关键 SLI",
                    "同步更新业务方状态");
        }
        return List.of(
                "先形成一页式证据摘要",
                "验证是否为偶发抖动",
                "给出可回退判断并保留证据");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String writeValueQuietly(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            logger.error("序列化工具结果失败", e);
            return "{\"success\":false,\"message\":\"工具输出序列化失败\"}";
        }
    }
}
