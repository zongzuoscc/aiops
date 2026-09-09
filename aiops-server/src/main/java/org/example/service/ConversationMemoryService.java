package org.example.service;

import org.example.config.ApiSecurityProperties;
import org.example.entity.ChatMessageEntity;
import org.example.entity.ChatSessionEntity;
import org.example.repository.ChatMessageRepository;
import org.example.repository.ChatSessionRepository;
import org.example.security.AuthContext;
import org.example.security.SessionAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final String SESSION_STATUS_ACTIVE = "ACTIVE";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String SESSION_ACCESS_ERROR = "会话不存在或无权访问";
    private static final int SUMMARY_MAX_LENGTH = 3000;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageStructuringService messageStructuringService;
    private final ApiSecurityProperties apiSecurityProperties;

    public ConversationMemoryService(ChatSessionRepository chatSessionRepository,
                                     ChatMessageRepository chatMessageRepository,
                                     MessageStructuringService messageStructuringService,
                                     ApiSecurityProperties apiSecurityProperties) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.messageStructuringService = messageStructuringService;
        this.apiSecurityProperties = apiSecurityProperties;
    }

    @Transactional
    public String resolveSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
    }

    @Transactional
    public String touchSession(String sessionId) {
        String finalSessionId = resolveSessionId(sessionId);
        ChatSessionEntity session = ensureSessionExists(finalSessionId);
        if (session.getNextSeqNo() <= 0) {
            long now = System.currentTimeMillis();
            session.setNextSeqNo(1);
            session.setUpdateTime(now);
            session.setLastActiveTime(now);
            chatSessionRepository.save(session);
        }
        return finalSessionId;
    }

    @Transactional(readOnly = true)
    public boolean sessionExists(String sessionId) {
        if (!isOwnerIsolationEnabled()) {
            return chatSessionRepository.existsById(sessionId);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return getSessionForCurrentOwnerRead(sessionId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRecentHistory(String sessionId, int maxPairs) {
        if (maxPairs <= 0) {
            return Collections.emptyList();
        }
        ChatSessionEntity session = ensureReadableSession(sessionId);
        if (session == null) {
            return Collections.emptyList();
        }

        int maxMessages = maxPairs * 2;
        List<ChatMessageEntity> messages = chatMessageRepository.findRecentBySessionId(
                session.getSessionId(), PageRequest.of(0, maxMessages)
        );
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        Collections.reverse(messages);
        List<Map<String, String>> history = new ArrayList<>(messages.size());
        for (ChatMessageEntity message : messages) {
            Map<String, String> item = new HashMap<>();
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            history.add(item);
        }
        return history;
    }

    @Transactional
    public void appendMessagePair(String sessionId, String userQuestion, String aiAnswer, String mode) {
        ChatSessionEntity session = ensureSessionForUpdate(sessionId);

        long now = System.currentTimeMillis();
        int baseSeq = session.getNextSeqNo();

        ChatMessageEntity userMessage = buildMessage(sessionId, baseSeq, ROLE_USER, userQuestion, mode, now);
        ChatMessageEntity assistantMessage = buildMessage(sessionId, baseSeq + 1, ROLE_ASSISTANT, aiAnswer, mode, now);

        chatMessageRepository.save(userMessage);
        chatMessageRepository.save(assistantMessage);

        session.setNextSeqNo(baseSeq + 2);
        session.setMessagePairCount(session.getMessagePairCount() + 1);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        chatSessionRepository.save(session);

        logger.debug("会话持久化成功 - SessionId: {}, 当前消息对数: {}", sessionId, session.getMessagePairCount());
    }

    @Transactional
    public void appendUserMessage(String sessionId, String userQuestion, String mode) {
        ChatSessionEntity session = ensureSessionForUpdate(sessionId);
        long now = System.currentTimeMillis();
        int seqNo = session.getNextSeqNo();

        ChatMessageEntity userMessage = buildMessage(sessionId, seqNo, ROLE_USER, userQuestion, mode, now);
        chatMessageRepository.save(userMessage);

        session.setNextSeqNo(seqNo + 1);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        chatSessionRepository.save(session);
    }

    @Transactional
    public void appendAssistantMessage(String sessionId, String aiAnswer, String mode) {
        ChatSessionEntity session = ensureSessionForUpdate(sessionId);
        long now = System.currentTimeMillis();
        int seqNo = session.getNextSeqNo();

        ChatMessageEntity assistantMessage = buildMessage(sessionId, seqNo, ROLE_ASSISTANT, aiAnswer, mode, now);
        chatMessageRepository.save(assistantMessage);

        session.setNextSeqNo(seqNo + 1);
        session.setMessagePairCount(session.getMessagePairCount() + 1);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        chatSessionRepository.save(session);
    }

    @Transactional
    public void clearSession(String sessionId) {
        ChatSessionEntity session = ensureReadableSession(sessionId);
        if (session == null) {
            return;
        }

        chatMessageRepository.deleteBySessionId(sessionId);
        long now = System.currentTimeMillis();
        session.setMessagePairCount(0);
        session.setNextSeqNo(0);
        session.setSummaryText(null);
        session.setSummaryUpdatedAt(null);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        chatSessionRepository.save(session);
    }

    @Transactional
    public boolean deleteSessionCompletely(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        ChatSessionEntity session = ensureReadableSession(sessionId);
        if (session == null) {
            return false;
        }

        chatMessageRepository.deleteBySessionId(sessionId);
        chatSessionRepository.deleteById(sessionId);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<SessionSummary> getSessionSummary(String sessionId) {
        return getSessionForCurrentOwnerRead(sessionId)
                .map(session ->
                        new SessionSummary(
                                session.getSessionId(),
                                session.getMessagePairCount(),
                                session.getCreateTime(),
                                session.getSummaryText(),
                                session.getCustomTitle()
                        )
                );
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> listRecentSessions(int limit, boolean includeEmpty) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Pageable pageable = PageRequest.of(0, safeLimit);

        List<ChatSessionEntity> sessions;
        if (isOwnerIsolationEnabled()) {
            String ownerId = currentOwnerId();
            sessions = includeEmpty
                    ? chatSessionRepository.findByOwnerIdOrderByLastActiveTimeDesc(ownerId, pageable)
                    : chatSessionRepository.findByOwnerIdAndNextSeqNoGreaterThanOrderByLastActiveTimeDesc(ownerId, 0, pageable);
        } else {
            sessions = includeEmpty
                    ? chatSessionRepository.findAllByOrderByLastActiveTimeDesc(pageable)
                    : chatSessionRepository.findByNextSeqNoGreaterThanOrderByLastActiveTimeDesc(0, pageable);
        }

        Map<String, SessionSummary> merged = new LinkedHashMap<>();
        for (ChatSessionEntity session : sessions) {
            merged.put(session.getSessionId(), new SessionSummary(
                    session.getSessionId(),
                    session.getMessagePairCount(),
                    session.getCreateTime(),
                    session.getSummaryText(),
                    session.getCustomTitle()
            ));
        }

        if (!isOwnerIsolationEnabled()) {
            // 兼容历史遗留数据：chat_message 中存在但 chat_session 中缺失的会话也返回给前端，确保可见可删
            List<Object[]> messageStats = chatMessageRepository.findSessionStatsByLatest(PageRequest.of(0, safeLimit * 3));
            for (Object[] row : messageStats) {
                if (row == null || row.length < 3 || row[0] == null) {
                    continue;
                }
                String sessionId = row[0].toString();
                if (sessionId.isBlank() || merged.containsKey(sessionId)) {
                    continue;
                }
                long lastCreatedAt = row[1] instanceof Number ? ((Number) row[1]).longValue() : System.currentTimeMillis();
                int messageCount = row[2] instanceof Number ? ((Number) row[2]).intValue() : 0;
                int pairCount = Math.max(1, messageCount / 2);
                merged.put(sessionId, new SessionSummary(sessionId, pairCount, lastCreatedAt, null, null));
            }
        }

        List<SessionSummary> result = new ArrayList<>(merged.values());
        if (result.size() > safeLimit) {
            return result.subList(0, safeLimit);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<MessageView> listSessionMessages(String sessionId, int limit) {
        ChatSessionEntity session = ensureReadableSession(sessionId);
        if (session == null) {
            return Collections.emptyList();
        }

        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<ChatMessageEntity> messages = chatMessageRepository.findBySessionIdOrderBySeqNoAsc(
                session.getSessionId(), PageRequest.of(0, safeLimit)
        );
        List<MessageView> result = new ArrayList<>(messages.size());
        for (ChatMessageEntity message : messages) {
            result.add(new MessageView(
                    message.getRole(),
                    message.getContent(),
                    message.getCreatedAt()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<String> getSessionSummaryText(String sessionId) {
        return getSessionForCurrentOwnerRead(sessionId)
                .map(ChatSessionEntity::getSummaryText)
                .filter(text -> text != null && !text.isBlank());
    }

    @Transactional
    public void renameSessionTitle(String sessionId, String customTitle) {
        ChatSessionEntity session = ensureReadableSession(sessionId);
        if (session == null) {
            throw new SessionAccessException(SESSION_ACCESS_ERROR);
        }

        String normalized = customTitle == null ? "" : customTitle.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (normalized.length() > 128) {
            normalized = normalized.substring(0, 128);
        }

        long now = System.currentTimeMillis();
        session.setCustomTitle(normalized);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        chatSessionRepository.save(session);
    }

    @Transactional
    public void updateSessionSummary(String sessionId, String summaryText) {
        if (summaryText == null || summaryText.isBlank()) {
            return;
        }

        ChatSessionEntity session = ensureSessionForUpdate(sessionId);
        String normalized = summaryText.trim();
        if (normalized.length() > SUMMARY_MAX_LENGTH) {
            normalized = normalized.substring(normalized.length() - SUMMARY_MAX_LENGTH);
        }
        long now = System.currentTimeMillis();
        session.setSummaryText(normalized);
        session.setSummaryUpdatedAt(now);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        chatSessionRepository.save(session);
    }

    private ChatMessageEntity buildMessage(String sessionId, int seqNo, String role, String content, String mode, long now) {
        ChatMessageEntity message = new ChatMessageEntity();
        String normalizedContent = content == null ? "" : content;
        MessageStructuringService.MessageStructure structure =
                messageStructuringService.extract(role, normalizedContent, mode);

        message.setSessionId(sessionId);
        message.setSeqNo(seqNo);
        message.setRole(role);
        message.setContent(normalizedContent);
        message.setMode(mode == null ? "agent" : mode);
        message.setIntent(structure.intent());
        message.setTopic(structure.topic());
        message.setMemoryType(structure.memoryType());
        message.setEntitiesJson(structure.entitiesJson());
        message.setKeywordsJson(structure.keywordsJson());
        message.setCreatedAt(now);
        return message;
    }

    private ChatSessionEntity ensureSessionForUpdate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (!isOwnerIsolationEnabled()) {
            return chatSessionRepository.findBySessionIdForUpdate(sessionId)
                    .orElseGet(() -> createSession(sessionId, null));
        }

        String ownerId = currentOwnerId();
        ChatSessionEntity session = chatSessionRepository.findBySessionIdForUpdate(sessionId).orElse(null);
        if (session != null) {
            ensureSessionBelongsToCurrentOwner(session, ownerId);
            return session;
        }

        Optional<ChatSessionEntity> legacySession = chatSessionRepository.findById(sessionId);
        if (legacySession.isPresent()) {
            ChatSessionEntity sessionFromLegacy = legacySession.get();
            if (isUnownedSession(sessionFromLegacy)) {
                sessionFromLegacy.setOwnerId(ownerId);
                return chatSessionRepository.save(sessionFromLegacy);
            }
            throw new SessionAccessException(SESSION_ACCESS_ERROR);
        }

        return createSession(sessionId, ownerId);
    }

    private ChatSessionEntity ensureSessionExists(String sessionId) {
        ChatSessionEntity session = ensureSessionForUpdate(sessionId);
        return session;
    }

    private ChatSessionEntity ensureReadableSession(String sessionId) {
        return getSessionForCurrentOwnerRead(sessionId).orElse(null);
    }

    private Optional<ChatSessionEntity> getSessionForCurrentOwnerRead(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        if (!isOwnerIsolationEnabled()) {
            return chatSessionRepository.findById(sessionId);
        }

        String ownerId = currentOwnerId();
        Optional<ChatSessionEntity> matched = chatSessionRepository.findBySessionIdAndOwnerId(sessionId, ownerId);
        if (matched.isPresent()) {
            return matched;
        }

        Optional<ChatSessionEntity> legacy = chatSessionRepository.findById(sessionId);
        if (legacy.isEmpty()) {
            return Optional.empty();
        }

        ChatSessionEntity legacySession = legacy.get();
        if (isUnownedSession(legacySession)) {
            legacySession.setOwnerId(ownerId);
            return Optional.of(chatSessionRepository.save(legacySession));
        }

        return Optional.empty();
    }

    private void ensureSessionBelongsToCurrentOwner(ChatSessionEntity session, String ownerId) {
        if (isUnownedSession(session)) {
            session.setOwnerId(ownerId);
            chatSessionRepository.save(session);
            return;
        }
        if (!ownerId.equals(session.getOwnerId())) {
            throw new SessionAccessException(SESSION_ACCESS_ERROR);
        }
    }

    private boolean isUnownedSession(ChatSessionEntity session) {
        return session.getOwnerId() == null || session.getOwnerId().isBlank();
    }

    private boolean isOwnerIsolationEnabled() {
        return apiSecurityProperties != null && apiSecurityProperties.isEnabled();
    }

    private String currentOwnerId() {
        return AuthContext.getCurrentOwnerId();
    }

    private ChatSessionEntity createSession(String sessionId, String ownerId) {
        ChatSessionEntity session = new ChatSessionEntity();
        long now = System.currentTimeMillis();
        session.setSessionId(sessionId);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        session.setLastActiveTime(now);
        session.setMessagePairCount(0);
        session.setNextSeqNo(0);
        session.setStatus(SESSION_STATUS_ACTIVE);
        if (isOwnerIsolationEnabled() && ownerId != null && !ownerId.isBlank()) {
            session.setOwnerId(ownerId);
        }
        return chatSessionRepository.save(session);
    }

    public record SessionSummary(String sessionId,
                               int messagePairCount,
                               long createTime,
                               String summaryText,
                               String customTitle) {
    }

    public record MessageView(String role, String content, long createdAt) {
    }
}
