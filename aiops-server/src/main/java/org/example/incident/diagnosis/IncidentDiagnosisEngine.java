package org.example.incident.diagnosis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.RunbookRecord;
import org.example.incident.persistence.RunbookRepository;
import org.example.ops.contract.ActionType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IncidentDiagnosisEngine {

    private static final String SYSTEM_PROMPT = """
            You are the diagnosis component of a Docker operations control plane.
            Return one JSON object only, with fields action, confidence, summary,
            rationale, and verification. Never invent evidence. action must be one of
            RESTART_CONTAINER, RECREATE_WITH_STABLE_IMAGE, or MANUAL_REQUIRED.
            Prefer MANUAL_REQUIRED when evidence conflicts or no runbook matches.
            """;

    private final RunbookRepository runbooks;
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectMapper objectMapper;

    public IncidentDiagnosisEngine(
            RunbookRepository runbooks,
            ObjectProvider<ChatModel> chatModels,
            ObjectMapper objectMapper) {
        this.runbooks = runbooks;
        this.chatModels = chatModels;
        this.objectMapper = objectMapper;
    }

    public DiagnosisPlan diagnose(IncidentRecord incident, EvidenceBundle evidence) {
        String evidenceJson = json(evidence);
        RunbookRecord runbook = runbooks.findByEnabledTrue().stream()
                .filter(item -> item.matches(incident.getTitle() + ' ' + evidenceJson))
                .findFirst()
                .orElse(null);
        if (runbook == null) {
            return new DiagnosisPlan(null, 0, "No matching runbook",
                    "The knowledge base has no approved remediation for this symptom.",
                    "Manual investigation required", null, false);
        }

        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            return runbookFallback(runbook);
        }

        String userPrompt = "Incident:\n" + json(incidentSummary(incident))
                + "\nEvidence:\n" + evidenceJson
                + "\nMatched runbook:\n" + json(runbookSummary(runbook));
        try {
            String response = ChatClient.create(chatModel)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            JsonNode plan = objectMapper.readTree(stripFence(response));
            String actionName = plan.path("action").asText("MANUAL_REQUIRED");
            ActionType action = "MANUAL_REQUIRED".equals(actionName)
                    ? null : ActionType.valueOf(actionName);
            return new DiagnosisPlan(
                    action,
                    plan.path("confidence").asDouble(0),
                    plan.path("summary").asText(),
                    plan.path("rationale").asText(),
                    plan.path("verification").asText("Check state and HTTP health"),
                    runbook.getId(),
                    true);
        } catch (RuntimeException | JsonProcessingException ex) {
            return runbookFallback(runbook);
        }
    }

    private DiagnosisPlan runbookFallback(RunbookRecord runbook) {
        return new DiagnosisPlan(
                runbook.getRecommendedAction(),
                runbook.getMinConfidence(),
                "Matched approved runbook: " + runbook.getName(),
                runbook.getDiagnosisHint(),
                "Verify container state and configured HTTP health endpoint",
                runbook.getId(),
                true);
    }

    private Object incidentSummary(IncidentRecord incident) {
        return java.util.Map.of(
                "id", incident.getId(),
                "serviceId", incident.getServiceId(),
                "severity", incident.getSeverity(),
                "title", incident.getTitle(),
                "rawEvent", incident.getRawEvent());
    }

    private Object runbookSummary(RunbookRecord runbook) {
        return java.util.Map.of(
                "id", runbook.getId(),
                "name", runbook.getName(),
                "hint", runbook.getDiagnosisHint(),
                "recommendedAction", runbook.getRecommendedAction().name(),
                "minimumConfidence", runbook.getMinConfidence());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int closing = trimmed.lastIndexOf("```");
            return trimmed.substring(firstLine + 1, closing).trim();
        }
        return trimmed;
    }
}
