package org.example.incident.config;

import org.example.incident.domain.IncidentStateMachine;
import org.example.incident.policy.AutomationPolicyEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AutonomousOpsConfiguration {

    @Bean(name = "opsAgentModelExecutor", destroyMethod = "shutdownNow")
    java.util.concurrent.ExecutorService opsAgentModelExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(2, 4, 60,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    IncidentStateMachine incidentStateMachine() {
        return new IncidentStateMachine();
    }

    @Bean
    AutomationPolicyEngine automationPolicyEngine() {
        return new AutomationPolicyEngine();
    }
}
