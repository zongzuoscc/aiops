package org.example.incident.application;

import org.example.incident.agent.PlannerExecutorRunner;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.notification.MailIncidentNotifier;
import org.example.incident.observability.IncidentMetrics;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.ManagedServiceRepository;
import org.example.incident.safety.RedisSafetyStateStore;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Owns task lifetime and the service lease; the Planner chooses the investigation steps. */
@Service
public class AutonomousIncidentOrchestrator {
    private static final Duration LEASE_TTL = Duration.ofMinutes(3);
    private final WorkflowPersistence persistence;
    private final ManagedServiceRepository services;
    private final PlannerExecutorRunner runner;
    private final RedisSafetyStateStore safety;
    private final MailIncidentNotifier notifier;
    private final IncidentMetrics metrics;
    private final TaskScheduler scheduler;

    public AutonomousIncidentOrchestrator(WorkflowPersistence persistence, ManagedServiceRepository services,
            PlannerExecutorRunner runner, RedisSafetyStateStore safety, MailIncidentNotifier notifier,
            IncidentMetrics metrics, TaskScheduler scheduler) {
        this.persistence = persistence;
        this.services = services;
        this.runner = runner;
        this.safety = safety;
        this.notifier = notifier;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    public void process(String incidentId) {
        IncidentRecord incident = persistence.incident(incidentId);
        if (terminal(incident.getStatus())) return;
        var service = services.findById(incident.getServiceId()).orElse(null);
        if (service == null) {
            persistence.manualRequired(incidentId, "No managed service is registered for this incident");
            notifyResult(persistence.incident(incidentId));
            return;
        }
        var lease = safety.acquireServiceLease(service.getId(), LEASE_TTL).orElse(null);
        if (lease == null) {
            var snapshot = safety.snapshot(service.getId(), Duration.ofHours(1));
            if (!snapshot.available()) {
                persistence.manualRequired(incidentId, "Redis is unavailable; concurrent autonomous work is not allowed");
                notifyResult(persistence.incident(incidentId));
            } else {
                persistence.enqueueResume(incidentId, Duration.ofSeconds(10));
            }
            return;
        }
        var renewal = scheduler.scheduleAtFixedRate(() -> safety.renew(lease, LEASE_TTL), Duration.ofMinutes(1));
        try {
            incident = persistence.incident(incidentId);
            if (!terminal(incident.getStatus())) runner.run(incident, service, lease);
            IncidentRecord result = persistence.incident(incidentId);
            if (terminal(result.getStatus())) notifyResult(result);
        } finally {
            if (renewal != null) renewal.cancel(false);
            safety.release(lease);
        }
    }

    private void notifyResult(IncidentRecord incident) {
        try {
            if (incident.getStatus() == IncidentStatus.RESOLVED) {
                metrics.resolved(Duration.between(incident.getCreatedAt(), Instant.now()));
                notifier.resolved(incident);
            } else if (incident.getStatus() == IncidentStatus.MANUAL_REQUIRED) {
                metrics.manual();
                notifier.manualRequired(incident, incident.getPolicyReason());
            }
        } catch (RuntimeException ex) {
            persistence.timeline(incident.getId(), "NOTIFICATION", "FAILED",
                    "Incident notification failed: " + ex.getClass().getSimpleName(), java.util.Map.of());
        }
    }

    private boolean terminal(IncidentStatus status) {
        return status == IncidentStatus.RESOLVED || status == IncidentStatus.MANUAL_REQUIRED
                || status == IncidentStatus.FAILED || status == IncidentStatus.SUPPRESSED;
    }
}
