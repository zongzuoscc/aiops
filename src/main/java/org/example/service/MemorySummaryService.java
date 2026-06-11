package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MemorySummaryService {

    private static final Logger logger = LoggerFactory.getLogger(MemorySummaryService.class);

    @Value("${memory.summary.enabled:true}")
    private boolean summaryEnabled;

    @Value("${memory.summary.max-length:2500}")
    private int maxSummaryLength;

    @Value("${memory.summary.trigger-pairs:4}")
    private int triggerPairs;

    @Value("${memory.summary.llm-enabled:true}")
    private boolean llmEnabled;

    @Value("${memory.summary.model:qwen3-max}")
    private String summaryModelName;

    @Value("${memory.summary.temperature:0.2}")
    private double summaryTemperature;

    @Value("${memory.summary.max-token:700}")
    private int summaryMaxToken;

    @Autowired
    private ChatService chatService;

    public boolean shouldUpdateSummary(int messagePairCount) {
        return summaryEnabled && messagePairCount > 0 && messagePairCount % triggerPairs == 0;
    }

    public String buildSummary(Optional<String> oldSummary, String question, String answer) {
        if (!summaryEnabled) {
            return oldSummary.orElse("");
        }

        if (llmEnabled) {
            try {
                String summary = buildSummaryByLlm(oldSummary.orElse(""), question, answer);
                if (!summary.isBlank()) {
                    return normalizeLength(summary);
                }
            } catch (Exception e) {
                logger.warn("LLM 摘要生成失败，回退到规则摘要: {}", e.getMessage());
            }
        }

        return buildSummaryByRule(oldSummary, question, answer);
    }

    public String appendCurrentTurnToSummary(String summary, String question, String answer) {
        String next = buildSummary(Optional.ofNullable(summary), question, answer);
        logger.debug("摘要长度更新为: {}", next.length());
        return next;
    }

    private String buildSummaryByLlm(String oldSummary, String question, String answer) {
        DashScopeApi api = chatService.createDashScopeApi();
        DashScopeChatModel summaryModel = chatService.createChatModel(
                api, summaryTemperature, summaryMaxToken, 0.8, summaryModelName
        );

        String systemPrompt = "你是会话记忆整理助手。请将已有摘要与新增一轮对话合并成稳定摘要。"
                + "输出要求：1) 只输出摘要正文；2) 保留用户偏好、约束、未完成任务、关键事实；"
                + "3) 删除寒暄和重复；4) 使用简洁中文，分号或短句组织；5) 最终长度不超过 "
                + maxSummaryLength + " 个字符。";

        String userPrompt = "已有摘要：\n"
                + blankToPlaceholder(oldSummary)
                + "\n\n新增对话：\nQ: " + compact(question)
                + "\nA: " + compact(answer)
                + "\n\n请输出更新后的摘要：";

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));

        ChatResponse response = summaryModel.call(prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return cleanupSummary(text);
    }

    private String buildSummaryByRule(Optional<String> oldSummary, String question, String answer) {
        StringBuilder sb = new StringBuilder();
        oldSummary.ifPresent(summary -> {
            if (!summary.isBlank()) {
                sb.append(summary.trim()).append("\n");
            }
        });
        sb.append("Q: ").append(compact(question)).append("\n");
        sb.append("A: ").append(compact(answer)).append("\n");
        return normalizeLength(sb.toString().trim());
    }

    private String cleanupSummary(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "");
            cleaned = cleaned.replaceAll("\\n?```$", "");
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String normalizeLength(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxSummaryLength) {
            return text;
        }
        return text.substring(text.length() - maxSummaryLength);
    }

    private String blankToPlaceholder(String text) {
        if (text == null || text.isBlank()) {
            return "（无）";
        }
        return text;
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        int limit = 400;
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit);
    }
}
