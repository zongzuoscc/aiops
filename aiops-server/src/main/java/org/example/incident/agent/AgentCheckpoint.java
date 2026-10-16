package org.example.incident.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.incident.diagnosis.DiagnosisPlan;
import org.example.ops.contract.ActionType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCheckpoint {
    private int agentVersion = 1;
    private int round;
    private int completedRound;
    private int toolCalls;
    private Instant startedAt;
    private AgentPlan decision;
    private List<AgentObservation> observations = new ArrayList<>();
    private boolean verified;
    private boolean rolledBack;
    private String finalReport;
    private String failureReason;

    public static AgentCheckpoint fresh(Instant now) {
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.startedAt = now;
        return checkpoint;
    }

    public void plan(AgentPlan plan) {
        round++;
        decision = plan;
    }

    public boolean pending() {
        return decision != null && decision.decision() == AgentPlan.Decision.EXECUTE
                && completedRound < round;
    }

    public void observe(AgentObservation observation) {
        observations.add(observation);
        completedRound = round;
    }

    public boolean stalled() {
        if (observations.size() < 3) return false;
        var recent = observations.subList(observations.size() - 3, observations.size());
        AgentObservation first = recent.get(0);
        return recent.stream().allMatch(item -> item.tool().equals(first.tool())
                && item.success() == first.success()
                && java.util.Objects.equals(item.summary(), first.summary())
                && java.util.Objects.equals(item.details(), first.details()));
    }

    // Keep the existing console's diagnosis projection while persisting the actual run checkpoint.
    public DiagnosisPlan getPlan() {
        if (decision == null) return null;
        ActionType action = switch (decision.tool() == null ? "" : decision.tool()) {
            case "restartContainer" -> ActionType.RESTART_CONTAINER;
            case "recreateWithStableImage" -> ActionType.RECREATE_WITH_STABLE_IMAGE;
            case "rollback" -> ActionType.RESTORE_SNAPSHOT;
            default -> null;
        };
        return new DiagnosisPlan(action, decision.confidence(), decision.summary(), decision.summary(),
                "Container state and HTTP health must be verified by code", decision.runbookId(),
                decision.runbookId() != null);
    }
}
