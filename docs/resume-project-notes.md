# Resume and Interview Notes

## Suggested project description

Built an event-driven autonomous Docker operations Agent using Spring Boot, Spring AI, MySQL, RabbitMQ, Redis, Vue 3, and Docker. The system ingests and deduplicates production alerts, collects container evidence, retrieves approved Runbooks, generates structured AI diagnoses, enforces deterministic safety policies, executes allowlisted remediation through an isolated signed gateway, and closes incidents only after post-action health verification or rollback.

## Strong resume bullets

- Replaced in-process asynchronous incident handling with a MySQL transactional Outbox and RabbitMQ at-least-once workflow, adding external-event and remediation idempotency across process restarts.
- Designed a 13-state incident state machine covering evidence collection, diagnosis, policy, execution, verification, retry, rollback, and manual escalation instead of marking AI report generation as resolution.
- Isolated Docker Socket access in a dedicated Java executor using HMAC signatures, nonce replay protection, dual target allowlists, fixed action enums, and a durable action journal.
- Implemented Redis-backed per-service leases, cooldowns, write-rate limits, and circuit breakers; Redis loss fails closed for all mutating actions.
- Added evidence-grounded Spring AI diagnosis with approved Runbook fallback, preventing the model from directly executing tools or bypassing deterministic policy.
- Built a responsive Vue 3 operations command center showing real incidents, policy decisions, action history, verification, rollback, and managed-service boundaries.

## Interview tradeoffs

### Why RabbitMQ instead of Kafka?

The workload is command-oriented with per-message acknowledgement, retries, dead-letter handling, and modest throughput. RabbitMQ is a better operational fit and matches the team's experience. Kafka would be justified for long-retention event replay, high-volume telemetry, or many independent consumers.

### Why MySQL plus Redis?

MySQL is the source of truth for auditable incident state. Redis stores short-lived coordination and safety state. Losing Redis must not lose incident history, and it intentionally disables writes until coordination is reliable again.

### Why not let the model call Docker tools?

Model output is probabilistic and prompt-influenced. The model proposes a structured action; deterministic code checks evidence, allowlists, target protection, confidence, cooldown, rate, circuit, and rollback before any mutation.

### What remains for production scale?

Use a managed secret store and mTLS between control plane and executors, add executor high availability with a shared durable journal, integrate centralized logs and traces, add SLO-driven alert correlation, and introduce a separately designed Kubernetes executor only after the Docker boundary is proven.
