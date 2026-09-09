package org.example.incident.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentStateMachineTest {
    private final IncidentStateMachine stateMachine = new IncidentStateMachine();

    @Test
    void followsTheAutonomousHappyPath() {
        assertThat(stateMachine.transition(IncidentStatus.RECEIVED, IncidentStatus.COLLECTING_EVIDENCE)).isEqualTo(IncidentStatus.COLLECTING_EVIDENCE);
        assertThat(stateMachine.transition(IncidentStatus.COLLECTING_EVIDENCE, IncidentStatus.DIAGNOSING)).isEqualTo(IncidentStatus.DIAGNOSING);
        assertThat(stateMachine.transition(IncidentStatus.DIAGNOSING, IncidentStatus.PLANNED)).isEqualTo(IncidentStatus.PLANNED);
        assertThat(stateMachine.transition(IncidentStatus.PLANNED, IncidentStatus.POLICY_CHECKING)).isEqualTo(IncidentStatus.POLICY_CHECKING);
        assertThat(stateMachine.transition(IncidentStatus.POLICY_CHECKING, IncidentStatus.EXECUTING)).isEqualTo(IncidentStatus.EXECUTING);
        assertThat(stateMachine.transition(IncidentStatus.EXECUTING, IncidentStatus.VERIFYING)).isEqualTo(IncidentStatus.VERIFYING);
        assertThat(stateMachine.transition(IncidentStatus.VERIFYING, IncidentStatus.RESOLVED)).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void rejectsSkippingFromReceivedToResolved() {
        assertThatThrownBy(() -> stateMachine.transition(IncidentStatus.RECEIVED, IncidentStatus.RESOLVED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RECEIVED").hasMessageContaining("RESOLVED");
    }

    @Test
    void terminalStatesCannotRestartTheWorkflow() {
        assertThatThrownBy(() -> stateMachine.transition(IncidentStatus.RESOLVED, IncidentStatus.RECEIVED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(IncidentStatus.MANUAL_REQUIRED, IncidentStatus.EXECUTING)).isInstanceOf(IllegalStateException.class);
    }
}
