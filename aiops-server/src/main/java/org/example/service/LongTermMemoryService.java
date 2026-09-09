package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.example.config.ApiSecurityProperties;
import org.example.constant.MilvusConstants;
import org.example.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class LongTermMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final Gson GSON = new Gson();

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final MessageStructuringService messageStructuringService;

    @Value("${memory.enabled:true}")
    private boolean memoryEnabled;

    @Value("${memory.store-enabled:true}")
    private boolean memoryStoreEnabled;

    @Value("${memory.recall-top-k:3}")
    private int recallTopK;

    @Value("${memory.recall-candidate-top-k:20}")
    private int recallCandidateTopK;

    @Value("${memory.recall.structured-filter-enabled:true}")
    private boolean structuredFilterEnabled;

    @Value("${memory.recall.rerank-enabled:true}")
    private boolean rerankEnabled;

    @Value("${memory.store.unit-max-length:700}")
    private int storeUnitMaxLength;

    @Value("${memory.store.unit-overlap:100}")
    private int storeUnitOverlap;

    @Value("${memory.store.max-units-per-turn:8}")
    private int storeMaxUnitsPerTurn;

    @Value("${memory.store.include-question-context:true}")
    private boolean storeIncludeQuestionContext;

    @Value("${memory.conflict.enabled:true}")
    private boolean conflictEnabled;

    @Value("${memory.conflict.preference-dedup-enabled:true}")
    private boolean preferenceDedupEnabled;

    @Value("${memory.conflict.query-limit:200}")
    private long conflictQueryLimit;

    private volatile Boolean chatMemorySupportsStructuredFields;
    private volatile Boolean chatMemorySupportsOwnerField;

    public LongTermMemoryService(MilvusServiceClient milvusClient,
                                 VectorEmbeddingService embeddingService,
                                 MessageStructuringService messageStructuringService,
                                 ApiSecurityProperties apiSecurityProperties) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.messageStructuringService = messageStructuringService;
        this.apiSecurityProperties = apiSecurityProperties;
    }

    private final ApiSecurityProperties apiSecurityProperties;

    public void storeConversationMemory(String sessionId, String question, String answer, String mode) {
        storeConversationMemory(sessionId, currentOwnerId(), question, answer, mode);
    }

    public void storeConversationMemory(String sessionId,
                                       String ownerId,
                                       String question,
                                       String answer,
                                       String mode) {
        if (!memoryEnabled || !memoryStoreEnabled) {
            return;
        }
        try {
            ensureCollectionLoaded();
            boolean shouldConstrainOwner = shouldConstrainOwner(ownerId);
            if (isOwnerFilterEnabled() && shouldConstrainOwner) {
                logger.debug("长期记忆存储将绑定 ownerId: {}", ownerId);
            }
            String normalizedMode = (mode == null || mode.isBlank()) ? "agent" : mode;
            MessageStructuringService.MessageStructure structure =
                    messageStructuringService.extract("user", question, normalizedMode);
            List<MemoryUnit> units = buildMemoryUnits(question, answer, structure);
            if (units.isEmpty()) {
                return;
            }

            boolean structuredReady = isChatMemoryStructuredFieldsReady();
            Set<String> conflictIds = listConflictMemoryIds(sessionId, ownerId, structure, structuredReady);
            String createdAt = Instant.now().toString();
            String turnId = UUID.randomUUID().toString();
            int totalUnits = Math.min(units.size(), Math.max(1, storeMaxUnitsPerTurn));

            int successCount = 0;
            for (int i = 0; i < totalUnits; i++) {
                MemoryUnit unit = units.get(i);
                if (insertMemoryUnit(sessionId, ownerId, normalizedMode, createdAt, turnId,
                        i, totalUnits, unit, structuredReady)) {
                    successCount++;
                }
            }
            logger.debug("长期记忆写入完成 - SessionId: {}, TurnId: {}, 成功: {}/{}",
                    sessionId, turnId, successCount, totalUnits);
            if (successCount > 0 && !conflictIds.isEmpty()) {
                deleteMemoryIds(conflictIds);
            }
        } catch (Exception e) {
            logger.warn("长期记忆写入异常: {}", e.getMessage());
        }
    }

    public List<String> recallRelevantMemories(String sessionId, String query) {
        return recallRelevantMemories(sessionId, currentOwnerId(), query);
    }

    public List<String> recallRelevantMemories(String sessionId, String ownerId, String query) {
        if (!memoryEnabled) {
            return Collections.emptyList();
        }
        try {
            ensureCollectionLoaded();

            List<Float> queryVector = embeddingService.generateQueryVector(query);
            String expr = buildSessionExprForMemory(sessionId, ownerId);
            boolean structuredReady = isChatMemoryStructuredFieldsReady();
            MessageStructuringService.MessageStructure queryStructure =
                    messageStructuringService.extract("user", query, "agent");

            List<MemoryCandidate> candidates = Collections.emptyList();

            if (structuredFilterEnabled && structuredReady) {
                String structuredExpr = buildStructuredExpr(expr, queryStructure);
                candidates = searchCandidatesByExpr(queryVector, structuredExpr, true);
                if (!candidates.isEmpty()) {
                    return toTopContents(rerankCandidates(candidates, queryStructure));
                }
            }

            candidates = searchCandidatesByExpr(queryVector, expr, structuredReady);
            return toTopContents(rerankCandidates(candidates, queryStructure));
        } catch (Exception e) {
            logger.warn("长期记忆检索异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public boolean deleteSessionMemories(String sessionId) {
        return deleteSessionMemories(sessionId, currentOwnerId());
    }

    public boolean deleteSessionMemories(String sessionId, String ownerId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            ensureCollectionLoaded();
            String expr = buildSessionExprForDeletion(sessionId, ownerId);
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();
            R<MutationResult> response = milvusClient.delete(deleteParam);
            if (response.getStatus() != 0) {
                logger.warn("删除会话长期记忆失败 - SessionId: {}, msg: {}", sessionId, response.getMessage());
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("删除会话长期记忆异常 - SessionId: {}, err: {}", sessionId, e.getMessage());
            return false;
        }
    }

    private void ensureCollectionLoaded() {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                        .build()
        );

        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载长期记忆 collection 失败: " + loadResponse.getMessage());
        }
    }

    private String buildMemoryText(String question, String answer) {
        return "用户问题: " + (question == null ? "" : question) + "\n"
                + "助手回答: " + (answer == null ? "" : answer);
    }

    private List<MemoryUnit> buildMemoryUnits(String question,
                                              String answer,
                                              MessageStructuringService.MessageStructure structure) {
        String normalizedQuestion = normalizeText(question);
        String normalizedAnswer = normalizeText(answer);
        List<MemoryUnit> units = new ArrayList<>();

        if (storeIncludeQuestionContext && !normalizedQuestion.isBlank()) {
            units.add(new MemoryUnit(
                    "用户问题: " + compact(normalizedQuestion, 260),
                    "question",
                    structure.intent(),
                    structure.topic(),
                    structure.memoryType(),
                    "question"
            ));
        }

        String qaSummary = buildMemoryText(
                compact(normalizedQuestion, 280),
                compact(normalizedAnswer, 520)
        );
        units.add(new MemoryUnit(
                qaSummary,
                "summary",
                structure.intent(),
                structure.topic(),
                structure.memoryType(),
                "qa_summary"
        ));

        List<String> answerChunks = splitTextByParagraph(normalizedAnswer, storeUnitMaxLength, storeUnitOverlap);
        int chunkIndex = 0;
        for (String chunk : answerChunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String chunkText;
            if (storeIncludeQuestionContext && !normalizedQuestion.isBlank()) {
                chunkText = "问题上下文: " + compact(normalizedQuestion, 120)
                        + "\n回答片段: " + compact(chunk, storeUnitMaxLength);
            } else {
                chunkText = "回答片段: " + compact(chunk, storeUnitMaxLength);
            }
            units.add(new MemoryUnit(
                    chunkText,
                    "ans_chunk",
                    structure.intent(),
                    structure.topic(),
                    structure.memoryType(),
                    "answer_chunk_" + chunkIndex
            ));
            chunkIndex++;
            if (units.size() >= storeMaxUnitsPerTurn) {
                break;
            }
        }

        return units;
    }

    private boolean insertMemoryUnit(String sessionId,
                                     String ownerId,
                                     String mode,
                                     String createdAt,
                                     String turnId,
                                     int unitIndex,
                                     int totalUnits,
                                     MemoryUnit unit,
                                     boolean structuredReady) {
        try {
            String memoryText = normalizeText(unit.content());
            if (memoryText.isBlank()) {
                return false;
            }
            if (memoryText.length() > MilvusConstants.CONTENT_MAX_LENGTH) {
                memoryText = memoryText.substring(0, MilvusConstants.CONTENT_MAX_LENGTH);
            }

            List<Float> vector = embeddingService.generateEmbedding(memoryText);
            String id = UUID.randomUUID().toString();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", sessionId);
            String normalizedOwnerId = ownerId == null ? "" : ownerId.trim();
            metadata.put("ownerId", normalizedOwnerId);
            metadata.put("mode", mode);
            metadata.put("createdAt", createdAt);
            metadata.put("type", "chat_memory");
            metadata.put("intent", unit.intent());
            metadata.put("topic", unit.topic());
            metadata.put("memoryType", unit.memoryType());
            metadata.put("unitType", unit.unitType());
            metadata.put("turnId", turnId);
            metadata.put("unitIndex", unitIndex);
            metadata.put("totalUnits", totalUnits);

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("session_id", Collections.singletonList(sessionId)));
            if (supportsChatMemoryOwnerField()) {
                fields.add(new InsertParam.Field("owner_id", Collections.singletonList(normalizedOwnerId)));
            }
            fields.add(new InsertParam.Field("role", Collections.singletonList(unit.role())));
            if (structuredReady) {
                fields.add(new InsertParam.Field("intent", Collections.singletonList(unit.intent())));
                fields.add(new InsertParam.Field("topic", Collections.singletonList(unit.topic())));
                fields.add(new InsertParam.Field("memory_type", Collections.singletonList(unit.memoryType())));
            }
            fields.add(new InsertParam.Field("content", Collections.singletonList(memoryText)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            JsonObject metadataJson = GSON.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);
            if (response.getStatus() != 0) {
                logger.warn("长期记忆单元写入失败: {}", response.getMessage());
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("长期记忆单元写入异常: {}", e.getMessage());
            return false;
        }
    }

    private List<String> splitTextByParagraph(String text, int maxLen, int overlap) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return Collections.emptyList();
        }
        if (maxLen <= 120) {
            maxLen = 120;
        }
        if (overlap < 0) {
            overlap = 0;
        }
        if (normalized.length() <= maxLen) {
            return List.of(normalized);
        }

        List<String> result = new ArrayList<>();
        String[] roughParts = normalized.split("(?<=。|！|？|;|；)\\s+|\\n\\n+");
        StringBuilder current = new StringBuilder();
        for (String part : roughParts) {
            String piece = part == null ? "" : part.trim();
            if (piece.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() + 1 > maxLen) {
                String chunk = current.toString().trim();
                if (!chunk.isBlank()) {
                    result.add(chunk);
                }
                String overlapText = getTail(chunk, overlap);
                current = new StringBuilder(overlapText);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            String chunk = current.toString().trim();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }
        }

        if (result.isEmpty()) {
            return List.of(compact(normalized, maxLen));
        }
        return result;
    }

    private String getTail(String text, int length) {
        if (text == null || text.isBlank() || length <= 0) {
            return "";
        }
        if (text.length() <= length) {
            return text;
        }
        return text.substring(text.length() - length).trim();
    }

    private String compact(String text, int limit) {
        String normalized = normalizeText(text);
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private Set<String> listConflictMemoryIds(String sessionId,
                                              String ownerId,
                                              MessageStructuringService.MessageStructure structure,
                                              boolean structuredReady) {
        if (!conflictEnabled || !preferenceDedupEnabled || !isPreferenceConflictCandidate(structure)) {
            return Collections.emptySet();
        }
        try {
            String expr = buildPreferenceConflictExpr(sessionId, ownerId, structure.topic(), structuredReady);
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                    .withExpr(expr)
                    .withOutFields(List.of("id"))
                    .withLimit(Math.max(1, conflictQueryLimit))
                    .build();

            R<QueryResults> response = milvusClient.query(queryParam);
            if (response.getStatus() != 0 || response.getData() == null) {
                logger.warn("查询冲突记忆失败: {}", response.getMessage());
                return Collections.emptySet();
            }

            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            if (wrapper.getRowCount() <= 0) {
                return Collections.emptySet();
            }

            Set<String> ids = new LinkedHashSet<>();
            for (QueryResultsWrapper.RowRecord row : wrapper.getRowRecords()) {
                if (row.contains("id")) {
                    Object idObj = row.get("id");
                    if (idObj != null && !idObj.toString().isBlank()) {
                        ids.add(idObj.toString().trim());
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            logger.warn("查询冲突记忆异常: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private void deleteMemoryIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> idList = ids.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());
        if (idList.isEmpty()) {
            return;
        }
        try {
            for (int from = 0; from < idList.size(); from += 50) {
                int to = Math.min(from + 50, idList.size());
                List<String> batch = idList.subList(from, to);
                String expr = buildIdInExpr(batch);
                DeleteParam deleteParam = DeleteParam.newBuilder()
                        .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                        .withExpr(expr)
                        .build();
                R<MutationResult> response = milvusClient.delete(deleteParam);
                if (response.getStatus() != 0) {
                    logger.warn("删除冲突记忆失败: {}", response.getMessage());
                }
            }
            logger.info("已清理冲突偏好记忆: {}", idList.size());
        } catch (Exception e) {
            logger.warn("清理冲突偏好记忆异常: {}", e.getMessage());
        }
    }

    private String buildIdInExpr(List<String> ids) {
        String joined = ids.stream()
                .map(this::escapeForExpr)
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        return "id in [" + joined + "]";
    }

    private boolean isPreferenceConflictCandidate(MessageStructuringService.MessageStructure structure) {
        String memoryType = structure.memoryType() == null ? "" : structure.memoryType().trim().toLowerCase();
        String intent = structure.intent() == null ? "" : structure.intent().trim().toLowerCase();
        return "user_preference".equals(memoryType) || "memory_setting_query".equals(intent);
    }

    private String buildPreferenceConflictExpr(String sessionId, String ownerId, String topic, boolean structuredReady) {
        String baseExpr = buildSessionExprForMemory(sessionId, ownerId);
        String normalizedTopic = (topic == null ? "" : topic.trim().toLowerCase());
        boolean specificTopic = !normalizedTopic.isBlank() && !"general".equals(normalizedTopic);

        if (structuredReady) {
            String expr = baseExpr + " and memory_type == \"user_preference\"";
            if (specificTopic) {
                expr += " and topic == \"" + escapeForExpr(normalizedTopic) + "\"";
            }
            return expr;
        }

        String expr = baseExpr + " and metadata[\"memoryType\"] == \"user_preference\"";
        if (specificTopic) {
            expr += " and metadata[\"topic\"] == \"" + escapeForExpr(normalizedTopic) + "\"";
        }
        return expr;
    }

    private String buildSessionExprForMemory(String sessionId, String ownerId) {
        return buildSessionExpr(sessionId, ownerId);
    }

    private String buildSessionExprForDeletion(String sessionId, String ownerId) {
        return buildSessionExpr(sessionId, ownerId);
    }

    private String buildSessionExpr(String sessionId, String ownerId) {
        String normalizedSessionId = sessionId == null ? "" : sessionId.trim();
        if (normalizedSessionId.isBlank()) {
            return "session_id == \"\"";
        }

        String expr = "session_id == \"" + escapeForExpr(normalizedSessionId) + "\"";
        if (!shouldConstrainOwner(ownerId)) {
            return expr;
        }

        if (!supportsChatMemoryOwnerField()) {
            return expr;
        }

        return expr + " and owner_id == \"" + escapeForExpr(ownerId) + "\"";
    }

    private boolean shouldConstrainOwner(String ownerId) {
        return isOwnerFilterEnabled() && ownerId != null && !ownerId.isBlank();
    }

    private boolean isOwnerFilterEnabled() {
        return apiSecurityProperties != null && apiSecurityProperties.isEnabled();
    }

    private boolean supportsChatMemoryOwnerField() {
        if (chatMemorySupportsOwnerField != null) {
            return chatMemorySupportsOwnerField;
        }
        synchronized (this) {
            if (chatMemorySupportsOwnerField != null) {
                return chatMemorySupportsOwnerField;
            }
            try {
                DescribeCollectionParam describeParam = DescribeCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                        .build();
                R<DescribeCollectionResponse> response = milvusClient.describeCollection(describeParam);
                if (response.getStatus() != 0 || response.getData() == null) {
                    chatMemorySupportsOwnerField = false;
                    return false;
                }
                Set<String> fieldNames = new HashSet<>();
                for (FieldSchema fieldSchema : response.getData().getSchema().getFieldsList()) {
                    fieldNames.add(fieldSchema.getName());
                }
                chatMemorySupportsOwnerField = fieldNames.contains("owner_id");
                return chatMemorySupportsOwnerField;
            } catch (Exception e) {
                logger.warn("检查 chat_memory owner 字段失败: {}", e.getMessage());
                chatMemorySupportsOwnerField = false;
                return false;
            }
        }
    }

    private String currentOwnerId() {
        return AuthContext.getCurrentOwnerId();
    }

    private List<MemoryCandidate> searchCandidatesByExpr(List<Float> queryVector, String expr, boolean includeStructuredOutFields) {
        List<String> outFields = includeStructuredOutFields
                ? List.of("id", "content", "session_id", "intent", "topic", "memory_type", "metadata")
                : List.of("id", "content", "session_id", "metadata");

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                .withVectorFieldName("vector")
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(Math.max(recallCandidateTopK, recallTopK))
                .withMetricType(io.milvus.param.MetricType.L2)
                .withOutFields(outFields)
                .withExpr(expr)
                .withParams("{\"nprobe\":10}")
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() != 0) {
            logger.warn("长期记忆检索失败: expr={}, msg={}", expr, response.getMessage());
            return Collections.emptyList();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        if (wrapper.getRowRecords(0).isEmpty()) {
            return Collections.emptyList();
        }

        List<?> contentValues = safeFieldData(wrapper, "content");
        List<?> intentValues = includeStructuredOutFields ? safeFieldData(wrapper, "intent") : Collections.emptyList();
        List<?> topicValues = includeStructuredOutFields ? safeFieldData(wrapper, "topic") : Collections.emptyList();
        List<?> memoryTypeValues = includeStructuredOutFields ? safeFieldData(wrapper, "memory_type") : Collections.emptyList();
        List<?> metadataValues = safeFieldData(wrapper, "metadata");
        List<SearchResultsWrapper.IDScore> idScores = safeIdScores(wrapper);

        int rowCount = wrapper.getRowRecords(0).size();
        List<MemoryCandidate> candidates = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            String content = toSafeString(contentValues, i);
            if (content.isBlank()) {
                continue;
            }

            String metadata = toSafeString(metadataValues, i);
            String intent = resolveStructuredValue(intentValues, i, metadata, "intent", "intent");
            String topic = resolveStructuredValue(topicValues, i, metadata, "topic", "topic");
            String memoryType = resolveStructuredValue(memoryTypeValues, i, metadata, "memoryType", "memory_type");
            float vectorScore = (i < idScores.size()) ? idScores.get(i).getScore() : Float.MAX_VALUE;

            candidates.add(new MemoryCandidate(content, intent, topic, memoryType, vectorScore));
        }
        return candidates;
    }

    private List<MemoryCandidate> rerankCandidates(List<MemoryCandidate> candidates,
                                                   MessageStructuringService.MessageStructure queryStructure) {
        if (candidates.isEmpty() || !rerankEnabled) {
            return candidates;
        }

        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        for (MemoryCandidate candidate : candidates) {
            if (candidate.vectorScore() < min) {
                min = candidate.vectorScore();
            }
            if (candidate.vectorScore() > max) {
                max = candidate.vectorScore();
            }
        }
        float range = Math.max(1e-6f, max - min);

        List<ScoredMemoryCandidate> scored = new ArrayList<>(candidates.size());
        for (MemoryCandidate candidate : candidates) {
            double simScore = (max - candidate.vectorScore()) / range;
            double score = simScore;

            if (!queryStructure.topic().isBlank() && queryStructure.topic().equalsIgnoreCase(candidate.topic())) {
                score += 0.6;
            }
            if (!queryStructure.intent().isBlank() && queryStructure.intent().equalsIgnoreCase(candidate.intent())) {
                score += 0.35;
            }
            if (isPreferMemoryType(candidate.memoryType())) {
                score += 0.25;
            }
            if ("memory_setting_query".equalsIgnoreCase(queryStructure.intent())
                    && "user_preference".equalsIgnoreCase(candidate.memoryType())) {
                score += 0.4;
            }

            scored.add(new ScoredMemoryCandidate(candidate, score));
        }

        scored.sort(Comparator.comparingDouble(ScoredMemoryCandidate::score).reversed());
        List<MemoryCandidate> reranked = new ArrayList<>(scored.size());
        for (ScoredMemoryCandidate item : scored) {
            reranked.add(item.candidate());
        }
        return reranked;
    }

    private String buildStructuredExpr(String baseExpr, MessageStructuringService.MessageStructure queryStructure) {
        List<String> clauses = new ArrayList<>();
        clauses.add(baseExpr);
        if (!"general".equalsIgnoreCase(queryStructure.topic())) {
            clauses.add("topic == \"" + escapeForExpr(queryStructure.topic()) + "\"");
        }
        if ("memory_setting_query".equalsIgnoreCase(queryStructure.intent())
                || "how_to_query".equalsIgnoreCase(queryStructure.intent())) {
            clauses.add("intent == \"" + escapeForExpr(queryStructure.intent()) + "\"");
        }
        return String.join(" and ", clauses);
    }

    private boolean isPreferMemoryType(String memoryType) {
        if (memoryType == null) {
            return false;
        }
        String normalized = memoryType.trim().toLowerCase();
        return "user_preference".equals(normalized)
                || "user_profile".equals(normalized)
                || "task_request".equals(normalized);
    }

    private List<String> toTopContents(List<MemoryCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (MemoryCandidate candidate : candidates) {
            ordered.add(candidate.content());
            if (ordered.size() >= recallTopK) {
                break;
            }
        }
        return new ArrayList<>(ordered);
    }

    private List<?> safeFieldData(SearchResultsWrapper wrapper, String fieldName) {
        try {
            return wrapper.getFieldData(fieldName, 0);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<SearchResultsWrapper.IDScore> safeIdScores(SearchResultsWrapper wrapper) {
        try {
            return wrapper.getIDScore(0);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String toSafeString(List<?> values, int idx) {
        if (values == null || idx < 0 || idx >= values.size()) {
            return "";
        }
        Object value = values.get(idx);
        return value == null ? "" : value.toString().trim();
    }

    private String resolveStructuredValue(List<?> fieldValues, int idx, String metadataJson, String... keys) {
        String directValue = toSafeString(fieldValues, idx);
        if (!directValue.isBlank()) {
            return directValue;
        }
        if (metadataJson == null || metadataJson.isBlank()) {
            return "";
        }
        try {
            JsonElement element = JsonParser.parseString(metadataJson);
            if (!element.isJsonObject()) {
                return "";
            }
            JsonObject obj = element.getAsJsonObject();
            for (String key : keys) {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    return obj.get(key).getAsString();
                }
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isChatMemoryStructuredFieldsReady() {
        if (chatMemorySupportsStructuredFields != null) {
            return chatMemorySupportsStructuredFields;
        }
        synchronized (this) {
            if (chatMemorySupportsStructuredFields != null) {
                return chatMemorySupportsStructuredFields;
            }
            try {
                DescribeCollectionParam describeParam = DescribeCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                        .build();
                R<DescribeCollectionResponse> response = milvusClient.describeCollection(describeParam);
                if (response.getStatus() != 0 || response.getData() == null) {
                    chatMemorySupportsStructuredFields = false;
                    return false;
                }
                Set<String> fieldNames = new HashSet<>();
                for (FieldSchema fieldSchema : response.getData().getSchema().getFieldsList()) {
                    fieldNames.add(fieldSchema.getName());
                }
                chatMemorySupportsStructuredFields = fieldNames.contains("intent")
                        && fieldNames.contains("topic")
                        && fieldNames.contains("memory_type");
                return chatMemorySupportsStructuredFields;
            } catch (Exception e) {
                logger.warn("检查 chat_memory 结构化字段失败: {}", e.getMessage());
                chatMemorySupportsStructuredFields = false;
                return false;
            }
        }
    }

    private String escapeForExpr(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record MemoryCandidate(
            String content,
            String intent,
            String topic,
            String memoryType,
            float vectorScore
    ) {
    }

    private record ScoredMemoryCandidate(
            MemoryCandidate candidate,
            double score
    ) {
    }

    private record MemoryUnit(
            String content,
            String role,
            String intent,
            String topic,
            String memoryType,
            String unitType
    ) {
    }
}
