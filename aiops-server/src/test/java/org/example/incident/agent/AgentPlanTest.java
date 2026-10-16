package org.example.incident.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPlanTest {
    @Test
    void rejectsAnExecutablePlanWithoutATool() {
        assertThatThrownBy(() -> new AgentPlan(AgentPlan.Decision.EXECUTE,
                "", "", null, 0.9, "query logs"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteConfidenceBeforeItCanBypassAPolicyThreshold() {
        assertThatThrownBy(() -> new AgentPlan(AgentPlan.Decision.EXECUTE,
                "restartContainer", "", "approved", Double.NaN, "restart"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsToolsOutsideTheOperationsCatalog() {
        assertThatThrownBy(() -> new AgentPlan(AgentPlan.Decision.EXECUTE,
                "runShell", "rm -rf /", null, 1, "execute"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
