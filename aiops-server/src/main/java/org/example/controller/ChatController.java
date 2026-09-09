package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.ConversationMemoryService;
import org.example.service.MemorySummaryService;
import org.example.service.RagService;
import org.example.service.VectorSearchService;
import org.example.security.SessionAccessException;
import org.example.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_WINDOW_SIZE = 6;
    private static final String CHAT_MODE_AGENT = "agent";
    private static final String CHAT_MODE_RAG = "rag";

    private final AiOpsService aiOpsService;
    private final ChatService chatService;
    private final RagService ragService;
    private final ConversationMemoryService conversationMemoryService;
    private final MemorySummaryService memorySummaryService;
    private final Optional<ToolCallbackProvider> tools;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatController(AiOpsService aiOpsService,
                          ChatService chatService,
                          RagService ragService,
                          ConversationMemoryService conversationMemoryService,
                          MemorySummaryService memorySummaryService,
                          Optional<ToolCallbackProvider> tools) {
        this.aiOpsService = aiOpsService;
        this.chatService = chatService;
        this.ragService = ragService;
        this.conversationMemoryService = conversationMemoryService;
        this.memorySummaryService = memorySummaryService;
        this.tools = tools;
    }

    /**
     * 普通对话接口（支持工具调用）
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        String sessionId = request == null ? null : request.getId();
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(request.getId(), "问题内容不能为空")));
            }

            sessionId = conversationMemoryService.touchSession(request.getId());
            List<Map<String, String>> history = conversationMemoryService.getRecentHistory(sessionId, MAX_WINDOW_SIZE);
            logger.info("会话历史消息对数: {}", history.size() / 2);
            List<String> recalledMemories = buildRecalledMemories(sessionId, request.getQuestion());
            logger.info("召回长期记忆数量: {}", recalledMemories.size());

            String mode = normalizeMode(request.getMode());
            logger.info("对话模式: {}", mode);
            conversationMemoryService.appendUserMessage(sessionId, request.getQuestion(), mode);

            String fullAnswer = CHAT_MODE_RAG.equals(mode)
                    ? executeRagChat(request.getQuestion(), history, recalledMemories)
                    : executeAgentChat(request.getQuestion(), history, recalledMemories);

            conversationMemoryService.appendAssistantMessage(sessionId, fullAnswer, mode);
            chatService.storeMemory(sessionId, request.getQuestion(), fullAnswer, mode);
            refreshSummaryIfNeeded(sessionId, request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}", sessionId);

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(sessionId, fullAnswer)));
        } catch (SessionAccessException e) {
            logger.warn("会话访问被拒绝 - SessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(sessionId, e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());
            if (request.getId() == null || request.getId().isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            if (!conversationMemoryService.sessionExists(request.getId())) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

            conversationMemoryService.clearSession(request.getId());
            return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
        } catch (SessionAccessException e) {
            logger.warn("清空会话历史被拒绝 - SessionId: {}", request == null ? null : request.getId(), e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 彻底删除会话（MySQL + Milvus 长期记忆）
     */
    @PostMapping("/chat/delete")
    public ResponseEntity<ApiResponse<DeleteResponse>> deleteChat(@RequestBody DeleteRequest request) {
        try {
            String sessionId = request == null ? null : request.getId();
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            boolean deletedSession = conversationMemoryService.deleteSessionCompletely(sessionId);
            boolean deletedMemory = true;
            boolean deleteMemory = request.getDeleteMemory() == null || request.getDeleteMemory();
            if (deleteMemory) {
                deletedMemory = chatService.deleteMemories(sessionId);
            }

            if (!deletedSession && (!deleteMemory || !deletedMemory)) {
                return ResponseEntity.ok(ApiResponse.error("未找到可删除的会话数据"));
            }

            DeleteResponse response = new DeleteResponse();
            response.setSessionId(sessionId);
            response.setDeletedSession(deletedSession);
            response.setDeletedMemory(deleteMemory && deletedMemory);
            response.setMemoryDeletionRequested(deleteMemory);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (SessionAccessException e) {
            logger.warn("删除会话被拒绝 - SessionId: {}", request == null ? null : request.getId(), e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("彻底删除会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 流式对话接口（SSE）
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);
        String ownerIdForAsync = AuthContext.getCurrentOwnerId();

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            AuthContext.setCurrentOwnerId(ownerIdForAsync);
            try {
                String mode = normalizeMode(request.getMode());
                String sessionId = conversationMemoryService.touchSession(request.getId());
                List<Map<String, String>> history = conversationMemoryService.getRecentHistory(sessionId, MAX_WINDOW_SIZE);
                List<String> recalledMemories = buildRecalledMemories(sessionId, request.getQuestion());
                logger.info("收到流式对话请求 - SessionId: {}, Mode: {}, 历史消息对数: {}",
                        sessionId, mode, history.size() / 2);
                conversationMemoryService.appendUserMessage(sessionId, request.getQuestion(), mode);

                if (CHAT_MODE_RAG.equals(mode)) {
                    streamRagChat(request.getQuestion(), history, recalledMemories, sessionId, mode, emitter);
                } else {
                    streamAgentChat(request.getQuestion(), history, recalledMemories, sessionId, mode, emitter);
                }
            } catch (SessionAccessException e) {
                logger.warn("流式对话访问被拒绝", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.complete();
            } catch (Exception e) {
                logger.error("流式对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            } finally {
                AuthContext.clear();
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE）
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L);
        String ownerIdForAsync = AuthContext.getCurrentOwnerId();

        executor.execute(() -> {
            AuthContext.setCurrentOwnerId(ownerIdForAsync);
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(dashScopeApi)
                        .defaultOptions(DashScopeChatOptions.builder()
                                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                                .withTemperature(0.3)
                                .withMaxToken(8000)
                                .withTopP(0.9)
                                .build())
                        .build();

                ToolCallback[] toolCallbacks = tools
                        .map(ToolCallbackProvider::getToolCallbacks)
                        .orElseGet(() -> new ToolCallback[0]);
                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));

                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);
                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }

                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                } else {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");
            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            } finally {
                AuthContext.clear();
            }
        });

        return emitter;
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);
            Optional<ConversationMemoryService.SessionSummary> summaryOptional =
                    conversationMemoryService.getSessionSummary(sessionId);
            if (summaryOptional.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

            ConversationMemoryService.SessionSummary summary = summaryOptional.get();
            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(summary.sessionId());
            response.setMessagePairCount(summary.messagePairCount());
            response.setCreateTime(summary.createTime());
            response.setSummaryText(summary.summaryText());
            response.setCustomTitle(summary.customTitle());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (SessionAccessException e) {
            logger.error("获取会话信息失败（会话访问被拒绝） - SessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取近期会话列表（默认仅返回有消息的会话）
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<List<SessionInfoResponse>>> listRecentSessions(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean includeEmpty) {
        try {
            List<ConversationMemoryService.SessionSummary> sessions =
                    conversationMemoryService.listRecentSessions(limit, includeEmpty);
            List<SessionInfoResponse> response = new ArrayList<>(sessions.size());
            for (ConversationMemoryService.SessionSummary summary : sessions) {
                SessionInfoResponse item = new SessionInfoResponse();
                item.setSessionId(summary.sessionId());
                item.setMessagePairCount(summary.messagePairCount());
                item.setCreateTime(summary.createTime());
                item.setSummaryText(summary.summaryText());
                item.setCustomTitle(summary.customTitle());
                response.add(item);
            }
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (SessionAccessException e) {
            logger.error("获取近期会话列表失败（访问被拒绝）", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("获取近期会话列表失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/chat/rename")
    public ResponseEntity<ApiResponse<String>> renameChat(@RequestBody RenameRequest request) {
        try {
            if (request == null || request.getId() == null || request.getId().isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }
            if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("标题不能为空"));
            }
            conversationMemoryService.renameSessionTitle(request.getId(), request.getTitle());
            return ResponseEntity.ok(ApiResponse.success("会话标题修改成功"));
        } catch (SessionAccessException e) {
            logger.error("修改会话标题失败（会话访问被拒绝） - SessionId: {}", request == null ? null : request.getId(), e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("修改会话标题失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取会话消息详情（用于前端刷新后恢复会话内容）
     */
    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<SessionMessageResponse>>> listSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "200") int limit) {
        try {
            if (!conversationMemoryService.sessionExists(sessionId)) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }
            List<ConversationMemoryService.MessageView> messages =
                    conversationMemoryService.listSessionMessages(sessionId, limit);
            List<SessionMessageResponse> response = new ArrayList<>(messages.size());
            for (ConversationMemoryService.MessageView message : messages) {
                SessionMessageResponse item = new SessionMessageResponse();
                item.setRole(message.role());
                item.setContent(message.content());
                item.setCreatedAt(message.createdAt());
                response.add(item);
            }
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (SessionAccessException e) {
            logger.error("获取会话消息详情失败（会话访问被拒绝） - SessionId: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("获取会话消息详情失败 - SessionId: {}", sessionId, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return CHAT_MODE_AGENT;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return CHAT_MODE_RAG.equals(normalized) ? CHAT_MODE_RAG : CHAT_MODE_AGENT;
    }

    private String executeAgentChat(String question, List<Map<String, String>> history, List<String> recalledMemories) throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
        chatService.logAvailableTools();
        String systemPrompt = chatService.buildSystemPrompt(history, recalledMemories);
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
        return chatService.executeChat(agent, question);
    }

    private String executeRagChat(String question, List<Map<String, String>> history, List<String> recalledMemories) {
        final String[] answerHolder = new String[1];
        final Exception[] errorHolder = new Exception[1];

        ragService.queryStream(question, history, recalledMemories, new RagService.StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                logger.debug("RAG 检索完成，命中数量: {}", results.size());
            }

            @Override
            public void onReasoningChunk(String chunk) {
            }

            @Override
            public void onContentChunk(String chunk) {
            }

            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                answerHolder[0] = fullContent;
            }

            @Override
            public void onError(Exception e) {
                errorHolder[0] = e;
            }
        });

        if (errorHolder[0] != null) {
            throw new RuntimeException("RAG 对话失败: " + errorHolder[0].getMessage(), errorHolder[0]);
        }
        return answerHolder[0] == null ? "" : answerHolder[0];
    }

    private void streamAgentChat(String question, List<Map<String, String>> history,
                                 List<String> recalledMemories,
                                 String sessionId, String mode, SseEmitter emitter) {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
        chatService.logAvailableTools();
        String systemPrompt = chatService.buildSystemPrompt(history, recalledMemories);
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
        StringBuilder fullAnswerBuilder = new StringBuilder();
        Flux<NodeOutput> stream;
        try {
            stream = agent.stream(question);
        } catch (Exception e) {
            logger.error("初始化 ReactAgent 流式对话失败", e);
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                logger.error("发送错误消息失败", ex);
            }
            emitter.completeWithError(e);
            return;
        }

        stream.subscribe(
                output -> {
                    try {
                        if (output instanceof StreamingOutput streamingOutput) {
                            OutputType type = streamingOutput.getOutputType();
                            if (type == OutputType.AGENT_MODEL_STREAMING) {
                                String chunk = streamingOutput.message().getText();
                                if (chunk != null && !chunk.isEmpty()) {
                                    fullAnswerBuilder.append(chunk);
                                    emitter.send(SseEmitter.event().name("message")
                                            .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                }
                            } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                logger.info("工具调用完成: {}", output.node());
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                error -> {
                    logger.error("ReactAgent 流式对话失败", error);
                    try {
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                    } catch (IOException ex) {
                        logger.error("发送错误消息失败", ex);
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    try {
                        String fullAnswer = fullAnswerBuilder.toString();
                        conversationMemoryService.appendAssistantMessage(sessionId, fullAnswer, mode);
                        chatService.storeMemory(sessionId, question, fullAnswer, mode);
                        refreshSummaryIfNeeded(sessionId, question, fullAnswer);
                        emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                }
        );
    }

    private void streamRagChat(String question, List<Map<String, String>> history,
                               List<String> recalledMemories,
                               String sessionId, String mode, SseEmitter emitter) {
        ragService.queryStream(question, history, recalledMemories, new RagService.StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                logger.debug("RAG 检索完成，命中数量: {}", results.size());
            }

            @Override
            public void onReasoningChunk(String chunk) {
            }

            @Override
            public void onContentChunk(String chunk) {
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                try {
                    String answer = fullContent == null ? "" : fullContent;
                    conversationMemoryService.appendAssistantMessage(sessionId, answer, mode);
                    chatService.storeMemory(sessionId, question, answer, mode);
                    refreshSummaryIfNeeded(sessionId, question, answer);
                    emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onError(Exception e) {
                logger.error("RAG 流式对话失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });
    }

    private List<String> buildRecalledMemories(String sessionId, String question) {
        List<String> merged = new ArrayList<>();
        conversationMemoryService.getSessionSummaryText(sessionId)
                .ifPresent(summary -> merged.add("会话摘要: " + summary));
        merged.addAll(chatService.recallMemories(sessionId, question));
        return merged;
    }

    private void refreshSummaryIfNeeded(String sessionId, String question, String answer) {
        Optional<ConversationMemoryService.SessionSummary> sessionSummaryOpt =
                conversationMemoryService.getSessionSummary(sessionId);
        if (sessionSummaryOpt.isEmpty()) {
            return;
        }

        ConversationMemoryService.SessionSummary sessionSummary = sessionSummaryOpt.get();
        if (!memorySummaryService.shouldUpdateSummary(sessionSummary.messagePairCount())) {
            return;
        }

        String latestSummary = memorySummaryService.appendCurrentTurnToSummary(
                sessionSummary.summaryText(), question, answer
        );
        conversationMemoryService.updateSessionSummary(sessionId, latestSummary);
        logger.info("会话摘要已更新 - SessionId: {}, 消息对数: {}", sessionId, sessionSummary.messagePairCount());
    }

    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Mode")
        @com.fasterxml.jackson.annotation.JsonAlias({"mode", "MODE"})
        private String Mode;
    }

    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    @Setter
    @Getter
    public static class DeleteRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "DeleteMemory")
        @com.fasterxml.jackson.annotation.JsonAlias({"deleteMemory", "delete_memory"})
        private Boolean DeleteMemory;
    }

    @Setter
    @Getter
    public static class DeleteResponse {
        private String sessionId;
        private boolean deletedSession;
        private boolean memoryDeletionRequested;
        private boolean deletedMemory;
    }

    @Setter
    @Getter
    public static class RenameRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Title")
        @com.fasterxml.jackson.annotation.JsonAlias({"title", "TITLE"})
        private String Title;
    }

    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
        private String summaryText;
        private String customTitle;
    }

    @Setter
    @Getter
    public static class SessionMessageResponse {
        private String role;
        private String content;
        private long createdAt;
    }

    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String sessionId;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String sessionId, String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setSessionId(sessionId);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String sessionId, String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setSessionId(sessionId);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    @Setter
    @Getter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }

    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }
    }
}
