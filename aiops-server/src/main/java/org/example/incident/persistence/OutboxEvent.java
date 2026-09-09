package org.example.incident.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Entity
@Table(name = "ops_event_outbox")
public class OutboxEvent {

    public enum Status { NEW, PUBLISHED }

    @Id
    private String id;
    @Column(name = "aggregate_type")
    private String aggregateType;
    @Column(name = "aggregate_id")
    private String aggregateId;
    @Column(name = "event_type")
    private String eventType;
    @Column(name = "routing_key")
    private String routingKey;
    @Column(columnDefinition = "json")
    private String payload;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int attempts;
    @Column(name = "available_at")
    private Instant availableAt;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
    }

    public static OutboxEvent create(
            String aggregateType,
            String aggregateId,
            String eventType,
            String routingKey,
            String payload) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID().toString();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.routingKey = routingKey;
        event.payload = payload;
        event.status = Status.NEW;
        event.availableAt = Instant.now();
        event.createdAt = event.availableAt;
        return event;
    }

    public void published() {
        status = Status.PUBLISHED;
        publishedAt = Instant.now();
        lastError = null;
    }

    public void failed(String error) {
        attempts++;
        lastError = error;
        availableAt = Instant.now().plusSeconds(Math.min(60, 1L << Math.min(attempts, 5)));
    }

    public OutboxEvent delay(Duration delay) {
        availableAt = Instant.now().plus(delay);
        return this;
    }

    public String getId() {
        return id;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }
}
