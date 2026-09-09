package org.example.incident.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.incident.application.AutonomousIncidentOrchestrator;
import org.example.ops.contract.IncidentWorkMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class IncidentWorkConsumer {

    private final AutonomousIncidentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public IncidentWorkConsumer(
            AutonomousIncidentOrchestrator orchestrator,
            ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = IncidentMessagingConfiguration.QUEUE, concurrency = "2")
    public void consume(String payload) throws JsonProcessingException {
        IncidentWorkMessage message = objectMapper.readValue(payload, IncidentWorkMessage.class);
        orchestrator.process(message.incidentId());
    }
}
