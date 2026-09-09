package org.example.executor.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ops.contract.ActionResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class ExecutorActionJournal {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ExecutorActionJournal(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public BeginResult begin(String requestId) {
        Optional<ActionResult> completed = completed(requestId);
        if (completed.isPresent()) {
            return new BeginResult(BeginState.COMPLETED, completed.get());
        }
        Integer existing = jdbc.query(
                "SELECT COUNT(*) FROM executor_action_journal WHERE request_id = ?",
                result -> result.next() ? result.getInt(1) : 0,
                requestId);
        if (existing != null && existing > 0) {
            return new BeginResult(BeginState.OUTCOME_UNKNOWN, null);
        }
        try {
            jdbc.update(
                    "INSERT INTO executor_action_journal(request_id, state, created_at) VALUES (?, 'STARTED', ?)",
                    requestId, Timestamp.from(Instant.now()));
            return new BeginResult(BeginState.STARTED, null);
        } catch (DuplicateKeyException ex) {
            return new BeginResult(BeginState.OUTCOME_UNKNOWN, null);
        }
    }

    public boolean claimNonce(String nonce, Instant expiresAt) {
        try {
            jdbc.update(
                    "INSERT INTO executor_nonce(nonce, expires_at) VALUES (?, ?)",
                    nonce, Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void complete(String requestId, ActionResult result) {
        try {
            jdbc.update(
                    "UPDATE executor_action_journal SET state = 'COMPLETED', result_json = ?, completed_at = ? "
                            + "WHERE request_id = ?",
                    objectMapper.writeValueAsString(result), Timestamp.from(Instant.now()), requestId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to persist executor result", ex);
        }
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void removeExpiredEntries() {
        jdbc.update(
                "DELETE FROM executor_action_journal WHERE state = 'COMPLETED' AND completed_at < ?",
                Timestamp.from(Instant.now().minusSeconds(7 * 24 * 60 * 60)));
        jdbc.update("DELETE FROM executor_nonce WHERE expires_at < ?", Timestamp.from(Instant.now()));
    }

    private Optional<ActionResult> completed(String requestId) {
        return jdbc.query(
                "SELECT result_json FROM executor_action_journal "
                        + "WHERE request_id = ? AND state = 'COMPLETED'",
                result -> {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(objectMapper.readValue(
                                result.getString("result_json"), ActionResult.class));
                    } catch (JsonProcessingException ex) {
                        throw new IllegalStateException("Executor journal is corrupt", ex);
                    }
                },
                requestId);
    }

    public enum BeginState { STARTED, COMPLETED, OUTCOME_UNKNOWN }

    public record BeginResult(BeginState state, ActionResult result) {
    }
}
