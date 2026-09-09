# Autonomous Docker AIOps Agent

This repository now contains a control plane for evidence-driven Docker incident remediation. It is intentionally not a remote shell wrapped in an LLM.

## What it does

1. Receives incidents through the generic API, Alertmanager webhook, or active health monitor.
2. Deduplicates the event in MySQL and publishes work through a transactional Outbox and RabbitMQ.
3. Collects container state, bounded logs, one-shot statistics, and HTTP health evidence.
4. Matches an approved Runbook and optionally asks the configured model for a structured diagnosis.
5. Evaluates a deterministic policy using allowlists, confidence, evidence count, cooldown, write limits, circuit state, and rollback availability.
6. Sends an HMAC-signed fixed action to the isolated `ops-executor` service.
7. Verifies container state and HTTP health. Only verified recovery becomes `RESOLVED`.
8. Restores the pre-change container snapshot when stable-image recreation fails verification.
9. Escalates unsupported or unsafe cases to operators through the event console and optional WeCom webhook.

## Modules

| Module | Responsibility |
|---|---|
| `aiops-server` | Incident control plane, AI diagnosis, policy, workflow, Outbox, APIs |
| `ops-contract` | Signed action protocol shared by control plane and executor |
| `ops-executor` | The only process allowed to access Docker Socket |
| `demo-service` | Failure-injection target used to demonstrate autonomous recovery |
| `frontend` | Local Vue incident command center, never deployed in the middleware stack |

## Local ports

The autonomous stack uses ports separate from the original containers:

| Component | Address |
|---|---|
| RabbitMQ AMQP | `127.0.0.1:25673` |
| RabbitMQ console | `http://127.0.0.1:25674` |
| Redis | `127.0.0.1:16380` |
| Ops executor | `http://127.0.0.1:18091` |
| Demo service | `http://127.0.0.1:18082` |
| Optional Prometheus | `http://127.0.0.1:19091` |
| Optional Grafana | `http://127.0.0.1:13001` |
| Local Vue console | `http://127.0.0.1:5173` |
| Java control plane | `http://127.0.0.1:9900` |

## Start

```bash
cp .env.autonomous.example .env.autonomous
docker compose --env-file .env.autonomous -f docker-compose.autonomous.yml up -d --build
```

Set `AIOPS_EXECUTOR_SHARED_SECRET` in `.env.autonomous` to at least 32 random characters, then start the Java control plane with the `autonomous` profile and the existing MySQL configuration:

```bash
mvn -pl aiops-server -am spring-boot:run \
  -Dspring-boot.run.profiles=autonomous
```

Start Vue locally:

```bash
cd frontend
npm ci
npm run dev
```

If API security is enabled, put the same `AIOPS_API_KEY` value in `frontend/.env.local` as `VITE_API_KEY`.

## Demo

The demo makes the health endpoint return `503`, creates an incident, and lets the Agent restart the container. Restarting resets the demo health state, after which the Agent verifies the service and resolves the incident.

```bash
bash scripts/demo-autonomous-recovery.sh
```

The model API key is optional for this demo. When `DASHSCOPE_API_KEY` is absent or the model call fails, the Agent can use only an approved matching Runbook. It never invents an action to keep automation moving.

## Safety boundary

Allowed operations are container state, bounded logs, one-shot statistics, configured HTTP health, allowlisted restart, stable-image recreate, and snapshot restore.

The executor has no endpoint for arbitrary shell, host commands, Docker prune, volume deletion, image deletion, or access to unregistered targets. Redis failure disables write actions. A policy rejection or uncertain executor outcome becomes `MANUAL_REQUIRED`.

See [service integration](docs/integrating-services.md) and [architecture](docs/autonomous-agent-architecture.md) for production use.
