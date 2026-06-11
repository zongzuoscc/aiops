package org.example.service;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MessageStructuringService {

    private static final Gson GSON = new Gson();

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?(?:ms|s|m|h|%|KB|MB|GB)?\\b");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{2,}");

    private static final List<String> DOMAIN_KEYWORDS = List.of(
            "milvus", "mysql", "rag", "agent", "spring", "maven", "docker",
            "向量数据库", "长期记忆", "会话", "流式", "摘要", "告警", "prometheus", "cls"
    );

    @Value("${memory.structured.enabled:true}")
    private boolean enabled;

    @Value("${memory.structured.max-keywords:8}")
    private int maxKeywords;

    @Value("${memory.structured.max-entities:8}")
    private int maxEntities;

    public MessageStructure extract(String role, String content, String mode) {
        if (!enabled) {
            return MessageStructure.empty();
        }

        String normalized = normalize(content);
        String intent = detectIntent(role, normalized);
        String topic = detectTopic(normalized);
        String memoryType = detectMemoryType(role, normalized);
        List<Map<String, String>> entities = extractEntities(normalized);
        List<String> keywords = extractKeywords(normalized, mode, topic);

        return new MessageStructure(
                intent,
                topic,
                memoryType,
                GSON.toJson(entities),
                GSON.toJson(keywords)
        );
    }

    private String detectIntent(String role, String text) {
        if ("assistant".equals(role)) {
            return "assistant_response";
        }
        if (containsAny(text, "如何", "怎么", "步骤", "教程", "帮我做")) {
            return "how_to_query";
        }
        if (containsAny(text, "报错", "异常", "故障", "原因", "排查", "定位")) {
            return "troubleshooting_query";
        }
        if (containsAny(text, "比较", "区别", "对比")) {
            return "comparison_query";
        }
        if (containsAny(text, "记住", "偏好", "喜欢", "长期")) {
            return "memory_setting_query";
        }
        return "general_query";
    }

    private String detectTopic(String text) {
        if (containsAny(text, "milvus", "向量")) {
            return "vector_database";
        }
        if (containsAny(text, "mysql", "sql", "数据库")) {
            return "relational_database";
        }
        if (containsAny(text, "rag")) {
            return "rag";
        }
        if (containsAny(text, "agent")) {
            return "agent";
        }
        if (containsAny(text, "spring", "maven")) {
            return "java_backend";
        }
        if (containsAny(text, "docker", "容器", "compose")) {
            return "infrastructure";
        }
        return "general";
    }

    private String detectMemoryType(String role, String text) {
        if ("assistant".equals(role)) {
            return "assistant_output";
        }
        if (containsAny(text, "我喜欢", "我的偏好", "记住我", "我常用")) {
            return "user_preference";
        }
        if (containsAny(text, "我叫", "我是", "我的")) {
            return "user_profile";
        }
        if (containsAny(text, "请你帮我", "帮我修改", "继续帮我做")) {
            return "task_request";
        }
        return "chat_fact";
    }

    private List<Map<String, String>> extractEntities(String text) {
        List<Map<String, String>> entities = new ArrayList<>();
        addMatches(entities, "url", URL_PATTERN, text);
        addMatches(entities, "uuid", UUID_PATTERN, text);
        addMatches(entities, "number", NUMBER_PATTERN, text);
        if (entities.size() > maxEntities) {
            return entities.subList(0, maxEntities);
        }
        return entities;
    }

    private void addMatches(List<Map<String, String>> entities, String type, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && entities.size() < maxEntities) {
            entities.add(Map.of("type", type, "value", matcher.group()));
        }
    }

    private List<String> extractKeywords(String text, String mode, String topic) {
        Set<String> keywords = new LinkedHashSet<>();
        if (mode != null && !mode.isBlank()) {
            keywords.add(mode.toLowerCase(Locale.ROOT));
        }
        if (topic != null && !topic.isBlank()) {
            keywords.add(topic);
        }

        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : DOMAIN_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                keywords.add(kw);
            }
        }

        Matcher tokenMatcher = TOKEN_PATTERN.matcher(text);
        while (tokenMatcher.find() && keywords.size() < maxKeywords) {
            keywords.add(tokenMatcher.group().toLowerCase(Locale.ROOT));
        }

        List<String> result = new ArrayList<>(keywords);
        if (result.size() > maxKeywords) {
            return result.subList(0, maxKeywords);
        }
        return result;
    }

    private boolean containsAny(String text, String... tokens) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    public record MessageStructure(
            String intent,
            String topic,
            String memoryType,
            String entitiesJson,
            String keywordsJson
    ) {
        static MessageStructure empty() {
            return new MessageStructure("unknown", "general", "chat_fact", "[]", "[]");
        }
    }
}

