package org.example.incident.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.api.IncidentIngestRequest;
import org.example.incident.persistence.AlertEventRepository;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.IncidentRecordRepository;
import org.example.incident.persistence.IncidentTimelineRepository;
import org.example.incident.persistence.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=${AIOPS_TEST_DB_URL:jdbc:mysql://host.docker.internal:33316/aiops_test?useSSL=false&allowPublicKeyRetrieval=true}",
        "spring.datasource.username=${AIOPS_TEST_DB_USER:aiops_test}",
        "spring.datasource.password=${AIOPS_TEST_DB_PASSWORD:aiops-test-password}",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=199",
        "aiops.incident-correlation.window-seconds=600"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IncidentIngestionService.class, IncidentIngestionServiceIntegrationTest.JsonConfiguration.class})
class IncidentIngestionServiceIntegrationTest {

    @Autowired
    private IncidentIngestionService ingestion;
    @Autowired
    private IncidentRecordRepository incidents;
    @Autowired
    private AlertEventRepository alertEvents;
    @Autowired
    private IncidentTimelineRepository timeline;
    @Autowired
    private OutboxEventRepository outbox;

    @Test
    void keepsExactRedeliveryIdempotent() {
        String serviceId = serviceId();
        IncidentIngestRequest request = request(serviceId, "event-001", "JvmMemoryHigh");

        IncidentRecord first = ingestion.ingest(request);
        IncidentRecord duplicate = ingestion.ingest(request);

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(alertEvents.findByIncidentIdOrderByReceivedAt(first.getId())).hasSize(1);
        assertThat(timeline.findByIncidentIdOrderByCreatedAt(first.getId())).hasSize(1);
        assertThat(outbox.countByAggregateId(first.getId())).isEqualTo(1);
    }

    @Test
    void correlatesDifferentEventIdsWithTheSameStableIdentity() {
        String serviceId = serviceId();

        IncidentRecord first = ingestion.ingest(request(serviceId, "event-001", "JvmMemoryHigh"));
        IncidentRecord related = ingestion.ingest(request(serviceId, "event-002", "JvmMemoryHigh"));

        assertThat(related.getId()).isEqualTo(first.getId());
        assertThat(alertEvents.findByIncidentIdOrderByReceivedAt(first.getId())).hasSize(2);
        assertThat(timeline.findByIncidentIdOrderByCreatedAt(first.getId()))
                .extracting("stage")
                .containsExactly("RECEIVED", "CORRELATED");
        assertThat(outbox.countByAggregateId(first.getId())).isEqualTo(1);
    }

    @Test
    void doesNotMergeDifferentAlertTypes() {
        String serviceId = serviceId();

        IncidentRecord memory = ingestion.ingest(request(serviceId, "event-001", "JvmMemoryHigh"));
        IncidentRecord latency = ingestion.ingest(request(serviceId, "event-002", "HttpLatencyHigh"));

        assertThat(latency.getId()).isNotEqualTo(memory.getId());
        assertThat(alertEvents.findByIncidentIdOrderByReceivedAt(memory.getId())).hasSize(1);
        assertThat(alertEvents.findByIncidentIdOrderByReceivedAt(latency.getId())).hasSize(1);
        assertThat(outbox.countByAggregateId(memory.getId())).isEqualTo(1);
        assertThat(outbox.countByAggregateId(latency.getId())).isEqualTo(1);
    }

    @Test
    void doesNotMergeDifferentServicesWithUuidIds() {
        IncidentRecord first = ingestion.ingest(request(serviceId(), "event-001", "JvmMemoryHigh"));
        IncidentRecord second = ingestion.ingest(request(serviceId(), "event-001", "JvmMemoryHigh"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesConcurrentEventsIntoOneIncident() throws Exception {
        String serviceId = serviceId();
        IncidentRecord[] results = ingestConcurrently(
                request(serviceId, "event-001", "JvmMemoryHigh"),
                request(serviceId, "event-002", "JvmMemoryHigh"));

        assertThat(results[1].getId()).isEqualTo(results[0].getId());
        assertThat(alertEvents.findByIncidentIdOrderByReceivedAt(results[0].getId())).hasSize(2);
        assertThat(incidents.findById(results[0].getId())).isPresent();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void keepsConcurrentExactRedeliveryIdempotent() throws Exception {
        String serviceId = serviceId();
        IncidentIngestRequest request = request(serviceId, "event-001", "JvmMemoryHigh");
        IncidentRecord[] results = ingestConcurrently(request, request);

        assertThat(results[1].getId()).isEqualTo(results[0].getId());
        assertThat(alertEvents.findByIncidentIdOrderByReceivedAt(results[0].getId())).hasSize(1);
        assertThat(outbox.countByAggregateId(results[0].getId())).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ingestsDifferentFingerprintsConcurrentlyWithoutDeadlocks() throws Exception {
        int concurrentRequests = 100;
        CountDownLatch ready = new CountDownLatch(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        List<Future<IncidentRecord>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < concurrentRequests; index++) {
                String serviceId = serviceId();
                futures.add(executor.submit(() -> ingestAfterSignal(
                        ready, start, request(serviceId, "event-001", "JvmMemoryHigh"))));
            }
            ready.await();
            start.countDown();

            List<String> incidentIds = new ArrayList<>();
            for (Future<IncidentRecord> future : futures) {
                incidentIds.add(future.get().getId());
            }
            assertThat(incidentIds).hasSize(concurrentRequests).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    private IncidentRecord[] ingestConcurrently(
            IncidentIngestRequest firstRequest,
            IncidentIngestRequest secondRequest) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IncidentRecord> first = executor.submit(() -> ingestAfterSignal(
                    ready, start, firstRequest));
            Future<IncidentRecord> second = executor.submit(() -> ingestAfterSignal(
                    ready, start, secondRequest));
            ready.await();
            start.countDown();
            return new IncidentRecord[]{first.get(), second.get()};
        } finally {
            executor.shutdownNow();
        }
    }

    private IncidentRecord ingestAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            IncidentIngestRequest request) throws InterruptedException {
        ready.countDown();
        start.await();
        return ingestion.ingest(request);
    }

    private IncidentIngestRequest request(String serviceId, String eventId, String alertName) {
        return new IncidentIngestRequest(
                "integration-test",
                serviceId + ':' + eventId,
                serviceId,
                "WARNING",
                alertName + " on instance 10.0.0.8:8080",
                "Test alert",
                Map.of(
                        "environment", "test",
                        "instance", "10.0.0.8:8080",
                        "alertname", alertName));
    }

    private String serviceId() {
        return "service-" + UUID.randomUUID();
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
