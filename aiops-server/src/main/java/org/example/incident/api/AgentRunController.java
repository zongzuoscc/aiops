package org.example.incident.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.example.service.AiOpsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/agent/runs")
public class AgentRunController {
    private final AiOpsService operations;

    public AgentRunController(AiOpsService operations) { this.operations = operations; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> start(@Valid @RequestBody StartRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return operations.start(request.serviceId(), request.task(), request.requestId(),
                request.allowRemediation(), key(apiKey, authorization));
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return operations.status(id, key(apiKey, authorization));
    }

    private String key(String apiKey, String authorization) {
        return apiKey != null ? apiKey : authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
    }

    public record StartRequest(@NotBlank String serviceId, @NotBlank @Size(max = 4000) String task,
                               @NotBlank @Size(max = 128) String requestId, boolean allowRemediation) { }
}
