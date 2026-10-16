package org.example.incident.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class IncidentStateMachine {

    private static final Map<IncidentStatus, Set<IncidentStatus>> TRANSITIONS = transitions();

    public boolean canTransition(IncidentStatus current, IncidentStatus next) {
        return TRANSITIONS.get(current).contains(next);
    }

    public IncidentStatus transition(IncidentStatus current, IncidentStatus next) {
        if (!canTransition(current, next)) {
            throw new IllegalStateException("Invalid incident transition: " + current + " -> " + next);
        }
        return next;
    }

    private static Map<IncidentStatus, Set<IncidentStatus>> transitions() {
        Map<IncidentStatus, Set<IncidentStatus>> transitions = new EnumMap<>(IncidentStatus.class);
        for (IncidentStatus status : IncidentStatus.values()) {
            transitions.put(status, EnumSet.noneOf(IncidentStatus.class));
        }

        allow(transitions, IncidentStatus.RECEIVED,
                IncidentStatus.COLLECTING_EVIDENCE, IncidentStatus.DIAGNOSING, IncidentStatus.SUPPRESSED,
                IncidentStatus.MANUAL_REQUIRED, IncidentStatus.FAILED);
        allow(transitions, IncidentStatus.COLLECTING_EVIDENCE,
                IncidentStatus.DIAGNOSING, IncidentStatus.RETRY_SCHEDULED,
                IncidentStatus.MANUAL_REQUIRED, IncidentStatus.FAILED);
        allow(transitions, IncidentStatus.DIAGNOSING,
                IncidentStatus.PLANNED, IncidentStatus.COLLECTING_EVIDENCE, IncidentStatus.VERIFYING,
                IncidentStatus.ROLLING_BACK, IncidentStatus.RETRY_SCHEDULED,
                IncidentStatus.MANUAL_REQUIRED, IncidentStatus.FAILED);
        allow(transitions, IncidentStatus.PLANNED,
                IncidentStatus.POLICY_CHECKING, IncidentStatus.COLLECTING_EVIDENCE, IncidentStatus.DIAGNOSING,
                IncidentStatus.VERIFYING, IncidentStatus.ROLLING_BACK, IncidentStatus.MANUAL_REQUIRED);
        allow(transitions, IncidentStatus.POLICY_CHECKING,
                IncidentStatus.EXECUTING, IncidentStatus.DIAGNOSING, IncidentStatus.MANUAL_REQUIRED,
                IncidentStatus.SUPPRESSED);
        allow(transitions, IncidentStatus.EXECUTING,
                IncidentStatus.VERIFYING, IncidentStatus.DIAGNOSING, IncidentStatus.RETRY_SCHEDULED,
                IncidentStatus.ROLLING_BACK, IncidentStatus.MANUAL_REQUIRED,
                IncidentStatus.FAILED);
        allow(transitions, IncidentStatus.VERIFYING,
                IncidentStatus.RESOLVED, IncidentStatus.DIAGNOSING, IncidentStatus.RETRY_SCHEDULED,
                IncidentStatus.ROLLING_BACK, IncidentStatus.MANUAL_REQUIRED,
                IncidentStatus.FAILED);
        allow(transitions, IncidentStatus.RETRY_SCHEDULED,
                IncidentStatus.COLLECTING_EVIDENCE, IncidentStatus.DIAGNOSING,
                IncidentStatus.EXECUTING, IncidentStatus.VERIFYING,
                IncidentStatus.MANUAL_REQUIRED, IncidentStatus.FAILED);
        allow(transitions, IncidentStatus.ROLLING_BACK,
                IncidentStatus.VERIFYING, IncidentStatus.DIAGNOSING, IncidentStatus.MANUAL_REQUIRED,
                IncidentStatus.FAILED);
        return Map.copyOf(transitions);
    }

    private static void allow(
            Map<IncidentStatus, Set<IncidentStatus>> transitions,
            IncidentStatus from,
            IncidentStatus... targets) {
        transitions.get(from).addAll(Set.of(targets));
    }
}
