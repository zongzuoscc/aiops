package org.example.incident.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCheckpointTest {
    @Test
    void aPendingPlanSurvivesSerializationWithoutBeingMarkedExecuted() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        AgentCheckpoint state = AgentCheckpoint.fresh(Instant.now());
        state.plan(new AgentPlan(AgentPlan.Decision.EXECUTE,
                "getContainerLogs", "", null, 0, "inspect logs"));
        AgentCheckpoint restored = mapper.readValue(mapper.writeValueAsString(state), AgentCheckpoint.class);
        assertThat(restored.pending()).isTrue();
        assertThat(restored.getRound()).isEqualTo(1);
        assertThat(restored.getDecision().tool()).isEqualTo("getContainerLogs");
    }

    @Test
    void aModelConclusionAloneCannotSatisfyRecoveryVerification() {
        AgentCheckpoint state = AgentCheckpoint.fresh(Instant.now());
        state.plan(new AgentPlan(AgentPlan.Decision.FINISH,
                null, "", null, 1, "everything is repaired"));
        assertThat(state.isVerified()).isFalse();
    }

    @Test
    void repeatingTheSameUnproductiveObservationIsDetected() {
        AgentCheckpoint state = AgentCheckpoint.fresh(Instant.now());
        for (int i = 0; i < 3; i++) {
            state.plan(new AgentPlan(AgentPlan.Decision.EXECUTE,
                    "getContainerLogs", "", null, 0, "try again"));
            state.observe(new AgentObservation(state.getRound(), "getContainerLogs", false,
                    "executor unavailable", Map.of()));
        }
        assertThat(state.stalled()).isTrue();
    }
}
