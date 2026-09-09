# Integrating an Existing Project

## 1. Expose a health endpoint

Provide a fast endpoint that represents application readiness, not merely whether the process exists. Return a non-2xx status when critical dependencies make the service unable to handle traffic.

Example Spring Boot target:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

## 2. Register the executor target

Add the service to `config/executor-targets.yml`. The health URL must be reachable from the executor container.

```yaml
aiops:
  executor:
    targets:
      payment-service:
        container-name: payment-api
        stable-image: registry.example.com/payment-api:stable
        health-url: http://payment-api:8080/actuator/health
        allowed-actions:
          - GET_CONTAINER_STATE
          - GET_CONTAINER_LOGS
          - GET_CONTAINER_STATS
          - CHECK_HTTP_HEALTH
          - RESTART_CONTAINER
```

Restart `ops-executor` after changing its target file.

## 3. Register the control-plane policy

```bash
curl -X POST http://localhost:9900/api/v2/services \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $AIOPS_API_KEY" \
  -d '{
    "id": "payment-service",
    "displayName": "Payment API",
    "containerName": "payment-api",
    "healthUrl": "http://payment-api:8080/actuator/health",
    "stableImage": "registry.example.com/payment-api:stable",
    "allowedActions": [
      "GET_CONTAINER_STATE",
      "GET_CONTAINER_LOGS",
      "GET_CONTAINER_STATS",
      "CHECK_HTTP_HEALTH",
      "RESTART_CONTAINER"
    ],
    "automationEnabled": true,
    "protectedTarget": false,
    "minConfidence": 0.9,
    "writeLimit": 2,
    "cooldownSeconds": 300
  }'
```

Begin with only `RESTART_CONTAINER`. Enable stable-image recreation after rollback has been exercised in a non-production environment.

## 4. Connect alerts

### Alertmanager

```yaml
receivers:
  - name: aiops-agent
    webhook_configs:
      - url: http://aiops-server:9900/api/v2/integrations/alertmanager
        send_resolved: false
        http_config:
          authorization:
            type: Bearer
            credentials: replace-with-the-control-plane-api-key
```

Each alert should include `service`, `severity`, and `alertname` labels. The `service` value must equal the registered service ID.

### Generic webhook

```bash
curl -X POST http://localhost:9900/api/v2/incidents \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $AIOPS_API_KEY" \
  -d '{
    "source": "order-api",
    "eventId": "production-order-api-20260822-001",
    "serviceId": "order-service",
    "severity": "critical",
    "title": "Health check returned 503",
    "description": "Readiness failed for three consecutive checks",
    "labels": {"environment": "production"}
  }'
```

The same `source` and `eventId` can be retried safely.

## 5. Add an approved Runbook

Runbooks live in `ops_runbook`. Keep symptom keywords specific, include the evidence required to distinguish similar failures, and limit the recommendation to an action supported by the executor. A missing match deliberately causes manual intervention.

## 6. Configure WeCom

Create an Enterprise WeChat group robot and set its webhook as `WECOM_WEBHOOK_URL`. The URL is never written to logs. Notifications are sent for verified recovery and manual escalation.

## 7. Roll out safely

1. Start with `automationEnabled=false` and inspect evidence and diagnosis quality.
2. Enable restart for one non-critical service with a high confidence threshold.
3. Inject a health failure and confirm cooldown, idempotency, notification, and verification.
4. Test an executor crash after journal `STARTED`; confirm it escalates instead of repeating the write.
5. Test stable-image recreation and rollback before enabling it in production.
6. Keep databases, queues, and the AIOps control plane itself protected from autonomous actions.
