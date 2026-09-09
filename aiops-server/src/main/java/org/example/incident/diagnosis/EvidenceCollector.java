package org.example.incident.diagnosis;

import org.example.incident.executor.ExecutorGateway;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EvidenceCollector {

    private static final List<ActionType> READ_ACTIONS = List.of(
            ActionType.GET_CONTAINER_STATE,
            ActionType.GET_CONTAINER_LOGS,
            ActionType.GET_CONTAINER_STATS,
            ActionType.CHECK_HTTP_HEALTH);

    private final ExecutorGateway executor;

    public EvidenceCollector(ExecutorGateway executor) {
        this.executor = executor;
    }

    public EvidenceBundle collect(String incidentId, String serviceId, int attempt) {
        List<ActionResult> observations = new ArrayList<>();
        for (ActionType action : READ_ACTIONS) {
            try {
                Map<String, String> parameters = action == ActionType.GET_CONTAINER_LOGS
                        ? Map.of("tail", "300") : Map.of();
                observations.add(executor.invoke(
                        incidentId + ":evidence:" + attempt + ':' + action.name(),
                        incidentId,
                        action,
                        serviceId,
                        parameters));
            } catch (RuntimeException ex) {
                observations.add(new ActionResult(
                        incidentId + ":evidence:" + attempt + ':' + action.name(), action, serviceId,
                        false, ex.getMessage(), Map.of(), Map.of(), java.time.Instant.now()));
            }
        }
        return new EvidenceBundle(List.copyOf(observations));
    }
}
