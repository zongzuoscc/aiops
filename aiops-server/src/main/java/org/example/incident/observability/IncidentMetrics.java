package org.example.incident.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class IncidentMetrics {

    private final Counter resolved;
    private final Counter manual;
    private final Counter rollback;
    private final Timer resolutionTime;

    public IncidentMetrics(MeterRegistry registry) {
        resolved = registry.counter("aiops.incidents.resolved");
        manual = registry.counter("aiops.incidents.manual_required");
        rollback = registry.counter("aiops.actions.rollback");
        resolutionTime = registry.timer("aiops.incident.resolution.time");
    }

    public void resolved(Duration duration) {
        resolved.increment();
        resolutionTime.record(duration);
    }

    public void manual() {
        manual.increment();
    }

    public void rollback() {
        rollback.increment();
    }
}
