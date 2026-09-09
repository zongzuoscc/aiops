package org.example.incident.config;

import org.example.incident.domain.IncidentStateMachine;
import org.example.incident.policy.AutomationPolicyEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AutonomousOpsConfiguration {

    @Bean
    IncidentStateMachine incidentStateMachine() {
        return new IncidentStateMachine();
    }

    @Bean
    AutomationPolicyEngine automationPolicyEngine() {
        return new AutomationPolicyEngine();
    }
}
