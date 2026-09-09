package org.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping
public class DemoFailureController {

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final String demoToken;

    public DemoFailureController(@Value("${demo.token:local-demo-token}") String demoToken) {
        this.demoToken = demoToken;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean current = healthy.get();
        return ResponseEntity.status(current ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", current ? "UP" : "DOWN",
                        "checkedAt", Instant.now().toString()));
    }

    @PostMapping("/demo/failure")
    public Map<String, Object> inject(
            @RequestHeader("X-Demo-Token") String token,
            @RequestParam(defaultValue = "unhealthy") String mode) {
        requireToken(token);
        if ("crash".equalsIgnoreCase(mode)) {
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(23);
            }, "demo-crash-trigger").start();
            return Map.of("mode", "crash", "accepted", true);
        }
        healthy.set(false);
        return Map.of("mode", "unhealthy", "accepted", true);
    }

    @PostMapping("/demo/recover")
    public Map<String, Object> recover(@RequestHeader("X-Demo-Token") String token) {
        requireToken(token);
        healthy.set(true);
        return Map.of("status", "UP");
    }

    private void requireToken(String token) {
        if (!demoToken.equals(token)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
