package org.example.incident.domain;

public enum IncidentStatus {
    RECEIVED,
    COLLECTING_EVIDENCE,
    DIAGNOSING,
    PLANNED,
    POLICY_CHECKING,
    EXECUTING,
    VERIFYING,
    RETRY_SCHEDULED,
    ROLLING_BACK,
    RESOLVED,
    MANUAL_REQUIRED,
    FAILED,
    SUPPRESSED
}
