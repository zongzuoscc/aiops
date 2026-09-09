package org.example.incident.monitoring;

import org.example.incident.api.IncidentIngestRequest;
import org.example.incident.application.IncidentIngestionService;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.executor.ExecutorGateway;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.ManagedServiceRecord;
import org.example.incident.persistence.ManagedServiceRepository;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "aiops.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class ManagedServiceHealthMonitor {

    private static final EnumSet<IncidentStatus> ACTIVE = EnumSet.of(
            IncidentStatus.RECEIVED,
            IncidentStatus.COLLECTING_EVIDENCE,
            IncidentStatus.DIAGNOSING,
            IncidentStatus.PLANNED,
            IncidentStatus.POLICY_CHECKING,
            IncidentStatus.EXECUTING,
            IncidentStatus.VERIFYING,
            IncidentStatus.RETRY_SCHEDULED,
            IncidentStatus.ROLLING_BACK);

    private final ManagedServiceRepository services;
    private final IncidentRecordRepository incidents;
    private final IncidentIngestionService ingestion;
    private final ExecutorGateway executor;
    private final int failureThreshold;
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    public ManagedServiceHealthMonitor(
            ManagedServiceRepository services,
            IncidentRecordRepository incidents,
            IncidentIngestionService ingestion,
            ExecutorGateway executor,
            @Value("${aiops.monitor.failure-threshold:3}") int failureThreshold) {
        this.services = services;
        this.incidents = incidents;
        this.ingestion = ingestion;
        this.executor = executor;
        this.failureThreshold = failureThreshold;
    }

    @Scheduled(fixedDelayString = "${aiops.monitor.interval-ms:30000}")
    public void inspectManagedServices() {
        for (ManagedServiceRecord service : services.findAll()) {
            check(service);
        }
    }

    private void check(ManagedServiceRecord service) {
        boolean healthy;
        String reason;
        try {
            ActionResult result = executor.invoke(
                    "health-monitor:" + service.getId() + ':' + System.nanoTime(),
                    "health-monitor",
                    ActionType.CHECK_HTTP_HEALTH,
                    service.getId(),
                    Map.of());
            Object status = result.data().get("statusCode");
            int statusCode = status instanceof Number number ? number.intValue() : 0;
            healthy = result.success() && statusCode >= 200 && statusCode < 400;
            reason = result.message() + ", status=" + statusCode;
        } catch (RuntimeException ex) {
            healthy = false;
            reason = ex.getMessage();
        }

        if (healthy) {
            consecutiveFailures.remove(service.getId());
            return;
        }
        int failures = consecutiveFailures.merge(service.getId(), 1, Integer::sum);
        if (failures < failureThreshold
                || incidents.existsByServiceIdAndStatusIn(service.getId(), ACTIVE)) {
            return;
        }
        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS);
        ingestion.ingest(new IncidentIngestRequest(
                "health-monitor",
                service.getId() + ':' + bucket,
                service.getId(),
                "CRITICAL",
                "Health check failed for " + service.getDisplayName(),
                reason,
                Map.of("detector", "active-health-monitor")));
    }
}
