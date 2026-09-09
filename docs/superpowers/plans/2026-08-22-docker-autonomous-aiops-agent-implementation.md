# Docker Autonomous AIOps Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the existing diagnostic prototype into a reliable, policy-constrained autonomous Docker incident remediation platform.

**Architecture:** Keep one repository but split the Java code into Maven modules for the control plane, shared execution contract, Docker execution gateway, and demo workload. MySQL remains the source of truth; RabbitMQ handles at-least-once tasks through an Outbox; Redis owns short-lived locks and circuit state; the Vue application becomes an incident command center.

**Tech Stack:** Java 17, Spring Boot, Spring AI, Spring AMQP, Spring Data JPA/Redis, Flyway, MySQL, RabbitMQ, Redis, Docker Java, Milvus, Vue 3, Vite, JUnit 5, Testcontainers.

## Global Constraints

- Docker only; Kubernetes, SSH, Kafka, arbitrary shell, host commands, volume deletion, and prune are out of scope.
- `aiops-server` must never mount the Docker socket.
- `ops-executor` must not connect to MySQL, Redis, RabbitMQ, DashScope, or WeCom.
- Docker writes require a managed-service allowlist, deterministic policy approval, idempotency, cooldown, and verification.
- Validation belongs at HTTP, RabbitMQ, model-output, database, and executor trust boundaries; avoid repeated internal validation and generic wrappers.
- MySQL is the durable source of truth, RabbitMQ delivery is at-least-once, and Redis data must be rebuildable.
- Existing user changes must be preserved while files are moved or split.

---

### Task 1: Multi-module foundation and domain core

**Files:**
- Modify: `pom.xml`
- Move: `src/` to `aiops-server/src/`
- Create: `aiops-server/pom.xml`
- Create: `ops-contract/pom.xml`
- Test: `aiops-server/src/test/java/org/example/incident/domain/IncidentStateMachineTest.java`
- Test: `aiops-server/src/test/java/org/example/incident/policy/AutomationPolicyEngineTest.java`

**Produces:** Explicit incident states, legal state transitions, action types, risk levels, policy decisions, and managed-service policy values.

- [ ] Write state-machine and policy tests and verify they fail because the new domain types do not exist.
- [ ] Implement the smallest domain model and deterministic policy engine that passes the tests.
- [ ] Convert the root build to Maven modules without changing existing runtime behavior.
- [ ] Run the domain tests and the existing server compilation.

### Task 2: Flyway, Outbox, RabbitMQ, and Redis

**Files:**
- Create: `aiops-server/src/main/resources/db/migration/V1__autonomous_aiops_schema.sql`
- Create: focused persistence, outbox, messaging, and lock classes under `org.example.incident`
- Modify: `aiops-server/src/main/resources/application.yml`
- Test: persistence and message idempotency tests under `aiops-server/src/test/java/org/example/incident`

**Produces:** Durable events and state transitions, transactional Outbox, at-least-once consumers, duplicate-message suppression, cooldowns, and circuit state.

- [ ] Write failing tests for alert deduplication, legal persisted transitions, duplicate execution IDs, and Redis-unavailable write suspension.
- [ ] Add the Flyway schema and typed repositories.
- [ ] Implement Outbox publishing, RabbitMQ topology, manual acknowledgements, TTL retries, and dead-letter routing.
- [ ] Implement Redis locks, cooldowns, counters, and automation circuit state.
- [ ] Run focused persistence and messaging tests.

### Task 3: Signed Docker execution gateway

**Files:**
- Create: `ops-contract/src/main/java/org/example/ops/contract/*`
- Create: `ops-executor/pom.xml`
- Create: focused executor classes under `ops-executor/src/main/java/org/example/executor`
- Test: contract and executor tests under both modules

**Produces:** Versioned action requests, canonical HMAC signatures, exact container resolution, protected targets, idempotent execution, read operations, restart, stable-image recreation, and snapshot restoration.

- [ ] Write failing tests for signatures, expiration, replay, allowlists, protected targets, and duplicate action IDs.
- [ ] Implement shared contracts and signing canonicalization.
- [ ] Implement internal executor endpoints and Docker Java actions without a generic exec endpoint.
- [ ] Run contract and executor tests against dedicated labelled Testcontainers only.

### Task 4: Autonomous incident orchestration

**Files:**
- Split and replace: `aiops-server/src/main/java/org/example/service/IncidentAutomationService.java`
- Modify: `aiops-server/src/main/java/org/example/service/AiOpsService.java`
- Create: evidence, agent, planning, execution, verification, rollback, and notification components under `org.example.incident`
- Test: orchestration contract tests under `aiops-server/src/test/java/org/example/incident/orchestration`

**Produces:** Current incident context reaches the Agent, structured model output is evidence-bound, policy-approved actions execute once, verification determines resolution, and failure rolls back or escalates.

- [ ] Write failing regression tests proving incident-specific context is passed and report generation alone cannot resolve an incident.
- [ ] Implement typed evidence and versioned structured Agent output.
- [ ] Connect diagnosis, policy, execution, delayed verification, rollback, circuit breaking, and WeCom notifications through RabbitMQ.
- [ ] Remove in-process cached thread pools and silent mock fallbacks from the incident path.
- [ ] Run orchestration tests with a fake chat model and mock executor.

### Task 5: Demo workload and observability

**Files:**
- Create: `demo-service/*`
- Create: `observability/prometheus.yml`
- Create: `observability/alertmanager.yml`
- Create: `observability/rules/*.yml`
- Modify: `docker-compose.yml`

**Produces:** Demo-only unhealthy, HTTP 500, CPU, memory, crash, and bad-image scenarios plus real Prometheus/Alertmanager webhooks and Micrometer metrics.

- [ ] Write failing tests proving fault routes exist only in the demo profile.
- [ ] Implement the demo workload and guarded fault controls.
- [ ] Add Actuator metrics and structured correlation IDs to the control plane.
- [ ] Add RabbitMQ, Prometheus, Alertmanager, executor, and demo services with non-conflicting host ports.
- [ ] Run a Docker smoke path from injected fault to verified recovery.

### Task 6: Vue incident command center

**Files:**
- Replace: `frontend/src/App.vue`
- Split: `frontend/src/` into API, router, stores, views, and focused components
- Modify: `frontend/package.json`
- Delete: `aiops-server/src/main/resources/static/*`
- Test: frontend unit and end-to-end tests

**Produces:** Real incident metrics, incident list/detail timeline, managed services, policies, retry/dead-letter tasks, runbooks, and secondary Agent chat.

- [ ] Write failing frontend tests for incident rendering, state labels, action timeline, and API errors.
- [ ] Implement routing, typed API access, state stores, and views using the real v1 APIs.
- [ ] Remove URL API keys, fake health state, fake KPIs, and the legacy static UI.
- [ ] Run frontend tests and production build.

### Task 7: Security, CI, documentation, and resume evidence

**Files:**
- Modify: `.gitignore`, `.env.aiops`, `README.md`, Dockerfiles, and API security configuration
- Create: `.env.aiops.example`
- Create: `.github/workflows/ci.yml`
- Create: operator and demo documentation under `docs/`

**Produces:** Safe defaults, ignored secrets, typed Problem Details, explicit CORS, health checks, reproducible CI, demo instructions, architecture documentation, and measurable resume evidence.

- [ ] Add tests for API status codes, API-key handling, CORS, and secret-safe logs.
- [ ] Enable secure defaults and remove complete webhook URLs from logs.
- [ ] Add backend, integration, frontend, image-build, and compose-smoke CI jobs.
- [ ] Document one-command startup, fault demonstrations, rollback, kill switch, and measured metrics.
- [ ] Run the full verification suite and perform a final code review before claiming completion.
