package org.example.incident.messaging;

import org.example.incident.persistence.OutboxEvent;
import org.example.incident.persistence.OutboxEventRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(OutboxEventRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${aiops.outbox.poll-delay:1000}")
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> events = repository.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAt(
                OutboxEvent.Status.NEW, Instant.now(), PageRequest.of(0, 50));
        for (OutboxEvent event : events) {
            try {
                CorrelationData correlation = new CorrelationData(event.getId());
                rabbitTemplate.convertAndSend(
                        IncidentMessagingConfiguration.EXCHANGE,
                        event.getRoutingKey(),
                        event.getPayload(),
                        message -> {
                            message.getMessageProperties().setMessageId(event.getId());
                            message.getMessageProperties().setContentType("application/json");
                            return message;
                        },
                        correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    throw new IllegalStateException("RabbitMQ rejected outbox message: " + confirm.getReason());
                }
                event.published();
            } catch (Exception ex) {
                event.failed(ex.getMessage());
            }
        }
    }
}
