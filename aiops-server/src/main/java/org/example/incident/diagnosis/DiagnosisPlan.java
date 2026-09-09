package org.example.incident.diagnosis;

import org.example.ops.contract.ActionType;

public record DiagnosisPlan(
        ActionType action,
        double confidence,
        String summary,
        String rationale,
        String verification,
        String runbookId,
        boolean knowledgeMatched) {

    public boolean actionable() {
        return action != null;
    }
}
