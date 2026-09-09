package org.example.incident.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.example.incident.persistence.ManagedServiceRecord;
import org.example.incident.persistence.ManagedServiceRepository;
import org.example.incident.executor.ExecutorGateway;
import org.example.ops.contract.ActionType;
import org.example.ops.contract.ActionResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/services")
public class ManagedServiceController {

    private final ManagedServiceRepository services;
    private final ExecutorGateway executor;

    public ManagedServiceController(
            ManagedServiceRepository services,
            ExecutorGateway executor) {
        this.services = services;
        this.executor = executor;
    }

    @GetMapping
    public List<ManagedServiceRecord> list() {
        return services.findAll();
    }

    @PostMapping
    public ManagedServiceRecord register(@Valid @RequestBody RegisterServiceRequest request) {
        try {
            ActionResult probe = executor.invoke(
                    "service-registration:" + UUID.randomUUID(),
                    "service-registration",
                    ActionType.GET_CONTAINER_STATE,
                    request.id(),
                    Map.of());
            if (!probe.success()) {
                throw new IllegalStateException(probe.message());
            }
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Authorize this service in executor-targets.yml before registering it",
                    ex);
        }
        return services.save(ManagedServiceRecord.create(
                request.id(), request.displayName(), request.containerName(), request.healthUrl(),
                request.stableImage(), request.allowedActions(), request.automationEnabled(),
                request.protectedTarget(), request.minConfidence(), request.writeLimit(),
                request.cooldownSeconds()));
    }

    public record RegisterServiceRequest(
            @NotBlank String id,
            @NotBlank String displayName,
            @NotBlank String containerName,
            String healthUrl,
            String stableImage,
            @NotEmpty Set<ActionType> allowedActions,
            boolean automationEnabled,
            boolean protectedTarget,
            @DecimalMin("0.5") @DecimalMax("1.0") double minConfidence,
            @Min(1) @Max(10) int writeLimit,
            @Min(30) @Max(86400) int cooldownSeconds) {
    }
}
