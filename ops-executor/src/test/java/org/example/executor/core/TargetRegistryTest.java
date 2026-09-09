package org.example.executor.core;

import org.example.executor.config.ExecutorProperties;
import org.example.ops.contract.ActionType;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetRegistryTest {

    @Test
    void enforcesExecutorSideActionAllowlist() {
        ExecutorProperties properties = new ExecutorProperties();
        ExecutorProperties.Target target = new ExecutorProperties.Target();
        target.setContainerName("payment-api");
        target.setAllowedActions(Set.of(ActionType.GET_CONTAINER_STATE));
        properties.setTargets(Map.of("payment", target));
        TargetRegistry registry = new TargetRegistry(properties);

        assertThat(registry.require("payment", ActionType.GET_CONTAINER_STATE)).isSameAs(target);
        assertThatThrownBy(() -> registry.require("payment", ActionType.RESTART_CONTAINER))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.require("unknown", ActionType.GET_CONTAINER_STATE))
                .isInstanceOf(ResponseStatusException.class);
    }
}
