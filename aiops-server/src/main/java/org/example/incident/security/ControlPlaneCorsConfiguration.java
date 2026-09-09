package org.example.incident.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ControlPlaneCorsConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public ControlPlaneCorsConfiguration(
            @Value("${aiops.control-plane.security.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}")
            String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v2/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type", "X-API-Key", "Authorization")
                .maxAge(3600);
    }
}
