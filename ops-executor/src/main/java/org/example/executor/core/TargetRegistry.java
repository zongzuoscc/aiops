package org.example.executor.core;

import org.example.executor.config.ExecutorProperties;
import org.example.ops.contract.ActionType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TargetRegistry {

    private final ExecutorProperties properties;

    public TargetRegistry(ExecutorProperties properties) {
        this.properties = properties;
    }

    public ExecutorProperties.Target require(String serviceId, ActionType action) {
        ExecutorProperties.Target target = properties.getTargets().get(serviceId);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Target is not registered");
        }
        if (!target.getAllowedActions().contains(action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Action is not allowed for the registered target");
        }
        return target;
    }
}
