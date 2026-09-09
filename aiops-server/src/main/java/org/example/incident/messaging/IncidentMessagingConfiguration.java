package org.example.incident.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class IncidentMessagingConfiguration {

    public static final String EXCHANGE = "aiops.incident";
    public static final String QUEUE = "aiops.incident.work";
    public static final String ROUTING_KEY = "incident.received";
    public static final String DEAD_LETTER_EXCHANGE = "aiops.incident.dlx";
    public static final String DEAD_LETTER_QUEUE = "aiops.incident.dead";
    public static final String DEAD_LETTER_ROUTING_KEY = "incident.dead";

    @Bean
    DirectExchange incidentExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue incidentWorkQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Binding incidentWorkBinding(
            @Qualifier("incidentWorkQueue") Queue incidentWorkQueue,
            @Qualifier("incidentExchange") DirectExchange incidentExchange) {
        return BindingBuilder.bind(incidentWorkQueue).to(incidentExchange).with(ROUTING_KEY);
    }

    @Bean
    DirectExchange incidentDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue incidentDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding incidentDeadLetterBinding(
            @Qualifier("incidentDeadLetterQueue") Queue incidentDeadLetterQueue,
            @Qualifier("incidentDeadLetterExchange") DirectExchange incidentDeadLetterExchange) {
        return BindingBuilder.bind(incidentDeadLetterQueue)
                .to(incidentDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}
