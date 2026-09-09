package org.example.incident.diagnosis;

import org.example.ops.contract.ActionResult;

import java.util.List;

public record EvidenceBundle(List<ActionResult> observations) {

    public int successfulObservationCount() {
        return (int) observations.stream().filter(ActionResult::success).count();
    }
}
