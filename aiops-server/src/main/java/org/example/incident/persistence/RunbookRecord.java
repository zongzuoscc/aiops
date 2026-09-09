package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.example.ops.contract.ActionType;

import java.util.Arrays;

@Entity
@Table(name = "ops_runbook")
public class RunbookRecord {

    @Id
    private String id;
    private String name;
    @Column(name = "symptom_keywords")
    private String symptomKeywords;
    @Column(name = "diagnosis_hint")
    private String diagnosisHint;
    @Column(name = "recommended_action")
    private String recommendedAction;
    @Column(name = "min_confidence")
    private double minConfidence;
    private boolean enabled;

    protected RunbookRecord() {
    }

    public boolean matches(String evidence) {
        String normalized = evidence.toLowerCase();
        return Arrays.stream(symptomKeywords.toLowerCase().split(","))
                .map(String::trim)
                .anyMatch(normalized::contains);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDiagnosisHint() {
        return diagnosisHint;
    }

    public ActionType getRecommendedAction() {
        return ActionType.valueOf(recommendedAction);
    }

    public double getMinConfidence() {
        return minConfidence;
    }
}
