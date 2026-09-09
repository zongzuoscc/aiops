package org.example.executor.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ops.contract.ActionRequest;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorActionJournalTest {

    private ExecutorActionJournal journal;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:journal-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        journal = new ExecutorActionJournal(
                new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void neverRestartsAnActionWhoseOutcomeIsUnknown() {
        assertThat(journal.begin("request-1").state())
                .isEqualTo(ExecutorActionJournal.BeginState.STARTED);
        assertThat(journal.begin("request-1").state())
                .isEqualTo(ExecutorActionJournal.BeginState.OUTCOME_UNKNOWN);
    }

    @Test
    void returnsCompletedResultForDuplicateRequest() {
        journal.begin("request-1");
        ActionRequest request = request("request-1");
        ActionResult result = ActionResult.success(request, "ok", Map.of("running", true), Map.of());
        journal.complete("request-1", result);

        ExecutorActionJournal.BeginResult duplicate = journal.begin("request-1");
        assertThat(duplicate.state()).isEqualTo(ExecutorActionJournal.BeginState.COMPLETED);
        assertThat(duplicate.result().success()).isTrue();
    }

    @Test
    void persistsNonceClaimsAgainstReplay() {
        Instant expiry = Instant.now().plusSeconds(120);
        assertThat(journal.claimNonce("nonce-1", expiry)).isTrue();
        assertThat(journal.claimNonce("nonce-1", expiry)).isFalse();
    }

    private ActionRequest request(String requestId) {
        return new ActionRequest(
                requestId, "incident-1", Instant.now(), "nonce-1", "key-1",
                ActionType.GET_CONTAINER_STATE, "payment", Map.of(), "signature");
    }
}
