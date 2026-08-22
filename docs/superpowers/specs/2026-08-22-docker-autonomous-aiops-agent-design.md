# Docker 全自动运维 Agent 设计

- 状态：已批准
- 日期：2026-08-22
- 主投方向：Java 后端 / AI 应用工程师
- 项目定位：面向 Docker 工作负载的证据驱动、策略约束、可自动验证与回滚的智能事件响应平台

## 1. 背景与结论

现有项目已经具备 RAG、多轮会话、多 Agent 编排、Prometheus 查询、告警 Webhook、事件持久化和企业微信通知，但仍属于智能诊断原型。当前主要问题包括：告警上下文未真正进入 Agent、生成报告即被视为已解决、真实日志接入未闭环、执行锁仅存在于单进程、没有受控 Docker 执行器、没有数据库迁移、测试和 CI 缺失，以及 Vue 页面尚未展示事件闭环。

本次重构将项目升级为 Docker 全自动运维 Agent。系统在预先登记的服务、动作和阈值范围内自动完成告警接入、证据收集、诊断、修复、验证、回滚、熔断和通知。大模型不直接获得 Docker 权限，所有写操作由确定性的 Java 策略引擎和独立执行网关控制。

选择该方向而不是直接接入 Kubernetes，是为了先在用户熟悉的 Docker 技术范围内建立可运行、可测试、可解释的完整闭环。架构保留执行目标接口，但第一版不实现 Kubernetes。

## 2. 目标

1. Alertmanager 或通用 Webhook 进入后，系统使用当前事件的真实证据完成诊断。
2. 对受支持的 Docker 故障实现无人干预的自动修复、恢复验证和结果通知。
3. RabbitMQ 重复投递、服务重启和多实例并发不会导致重复操作容器。
4. 大模型只能生成结构化动作建议，不能绕过策略、执行 Shell 或扩大权限。
5. 每次诊断、策略判断、Docker 操作、验证和回滚均可审计。
6. Vue 控制台展示真实事件、证据、动作和指标，不使用固定在线状态或虚假 KPI。
7. 通过测试、CI、演示故障和实测指标形成可用于简历和面试的工程证据。

## 3. 工程原则

### 3.1 允许大规模重构

现有目录和大文件不作为兼容边界。可以迁移包、拆分服务、替换状态模型和删除旧静态前端。需要保留的是有效业务能力和数据语义，而不是当前类名、接口形状或历史实现。

### 3.2 不进行过度防御性编程

1. 只在外部信任边界做完整校验：HTTP、RabbitMQ、模型输出、Docker 网关和数据库迁移。
2. 内部领域对象通过构造器和状态机保证合法，不在每层重复校验同一字段。
3. 不为所有方法创建通用 `Result`、多层包装器或无业务含义的 Manager/Helper。
4. 不在 Controller、Service、Repository 三层重复捕获同一个异常；异常统一在边界转换。
5. 不使用 `catch (Exception)` 后返回空集合、Mock 数据或成功响应掩盖故障。
6. 一个失败只由一个组件负责重试，禁止 HTTP、业务服务和消息消费者同时重试。
7. 只有两层写操作检查：控制面的业务策略检查和执行面的能力检查。除此之外不重复堆叠规则。
8. 不提前实现 Kubernetes、SSH、多宿主机和通用插件框架。
9. 不保留已被 Vue 替代的旧静态前端和重复 API。
10. 抽象必须对应真实变化点。第一版只抽象执行目标接口，不实现空壳适配器。

## 4. 总体架构

仓库重构为 Maven 多模块项目：

```text
aiops-upgrade/
├── pom.xml
├── aiops-server/
│   ├── pom.xml
│   └── src/
├── ops-contract/
│   ├── pom.xml
│   └── src/
├── ops-executor/
│   ├── pom.xml
│   └── src/
├── demo-service/
│   ├── pom.xml
│   └── src/
├── frontend/
├── docker-compose.yml
└── docs/
```

`ops-contract` 仅存放控制面和执行面共享的动作枚举、请求响应 DTO、签名规范和错误码。它不包含通用工具类、数据库实体或业务服务。

```text
Alertmanager / Webhook
          |
          v
   aiops-server API
          |
   MySQL transaction
   incident + outbox
          |
          v
      RabbitMQ
          |
          v
  Evidence -> Agent -> ActionPlan -> Policy
                                      |
                                      v
                              ops-executor
                                      |
                                Docker Engine
                                      |
                                      v
                              Verify / Rollback
                                      |
                         MySQL + WeCom + Vue
```

## 5. 服务职责

### 5.1 aiops-server

`aiops-server` 是控制面，负责：

- 告警接入、标准化、指纹计算和幂等落库。
- 托管服务、自动化策略和 Runbook 管理。
- 证据采集编排和证据持久化。
- Spring AI Agent 调用和结构化输出校验。
- 事件状态机、策略判断和任务调度。
- Outbox 发布、RabbitMQ 消费、重试和死信处理。
- 调用 `ops-executor`，但不挂载 Docker Socket。
- 修复验证、回滚决策、熔断和企业微信通知。
- 为 Vue 提供事件、服务、策略、任务和指标 API。

### 5.2 ops-executor

`ops-executor` 是执行面，负责：

- 独占挂载 `/var/run/docker.sock`。
- 通过 Docker Java API 读取和操作容器。
- 校验 HMAC、时间戳、Nonce、动作类型、目标白名单和幂等键。
- 执行固定动作，不提供任意 Shell 或通用 Docker API 代理。
- 在写操作前返回并保存容器快照所需信息。
- 返回标准化执行结果和 Docker 原始错误摘要。

`ops-executor` 不连接 MySQL、Redis、RabbitMQ、DashScope 或企业微信，不接收浏览器请求，也不映射宿主机端口。

### 5.3 demo-service

`demo-service` 是 Spring Boot 演示应用，只在 `demo` Profile 下开放故障注入能力：

- 健康检查失败。
- 持续返回 HTTP 500。
- CPU 高负载。
- 内存高占用。
- 进程退出。
- 错误版本启动失败。

故障接口必须使用 Demo 专用密钥，并且生产 Profile 不注册相关 Bean 和路由。

### 5.4 frontend

Vue 前端通过 Vite 在本地运行，不作为默认 Docker Compose 服务。生产镜像可以保留为可选 Profile，但不影响本地开发。后端 `static` 下旧页面删除。

## 6. 核心数据流

1. 监控系统向 `/api/v1/incidents/webhooks/alertmanager` 或通用 Webhook 发送告警。
2. `IncidentIngestionService` 标准化字段，计算 `owner + source + externalId/fingerprint` 幂等键。
3. 同一数据库事务写入 `incident_event` 和 `outbox_event(incident.received)`。
4. Outbox Publisher 将事件发送到 RabbitMQ，收到 Publisher Confirm 后标记已发布。
5. Diagnosis Consumer 领取任务，状态进入 `COLLECTING_EVIDENCE`。
6. `EvidenceCollector` 通过只读执行接口、Prometheus 和知识库获取容器状态、日志、指标与 Runbook。
7. `AgentOrchestrator` 将当前事件和证据传入 Agent，生成符合 JSON Schema 的诊断和 `ActionPlan`。
8. `PolicyEngine` 使用 Java 规则判断目标、动作、频率、证据和回滚条件。
9. 允许执行时写入 `action_execution` 和 Outbox；拒绝时进入 `MANUAL_REQUIRED`。
10. Execution Consumer 获取 Redis 锁，再通过签名 HTTP 请求调用 `ops-executor`。
11. 动作完成后投递延迟验证任务，`VerificationEngine` 在窗口结束后检查恢复条件。
12. 验证成功进入 `RESOLVED`；失败进入 `ROLLING_BACK`、备用动作或 `MANUAL_REQUIRED`。
13. 每次终态和关键动作投递通知任务，企业微信收到诊断、动作和最终结果。

## 7. 事件状态机

主路径：

```text
RECEIVED
  -> COLLECTING_EVIDENCE
  -> DIAGNOSING
  -> PLANNED
  -> POLICY_CHECKING
  -> EXECUTING
  -> VERIFYING
  -> RESOLVED
```

异常状态：

- `RETRY_SCHEDULED`：可恢复的外部依赖失败，等待 RabbitMQ 延迟重试。
- `ROLLING_BACK`：修复动作未通过验证，正在恢复快照或稳定镜像。
- `MANUAL_REQUIRED`：不支持的场景、策略拒绝、回滚不可用或重试耗尽。
- `FAILED`：内部不可恢复错误，且事件无法安全继续。
- `SUPPRESSED`：重复告警、维护窗口或熔断期间不执行。

状态迁移只能通过单一 `IncidentStateMachine` 完成。实体 Setter 不对外暴露任意状态修改。每次迁移记录来源状态、目标状态、原因、操作者、版本和时间。

## 8. 数据模型

### 8.1 managed_service

- `id`
- `owner_id`
- `service_name`
- `container_name`
- `container_label_selector`
- `health_url`
- `stable_image`
- `allowed_actions`
- `cpu_threshold`
- `memory_threshold`
- `error_threshold`
- `restart_limit`
- `cooldown_seconds`
- `automation_enabled`
- `notification_owner`
- `version`

容器名称或 Label 必须解析为唯一容器。零个或多个匹配都拒绝写操作。

### 8.2 incident_event

- `event_id`
- `owner_id`
- `external_event_id`
- `fingerprint`
- `source`
- `severity`
- `service_name`
- `title`
- `summary`
- `status`
- `attempts`
- `next_attempt_at`
- `last_error_code`
- `last_error_message`
- `created_at`
- `updated_at`
- `resolved_at`
- `version`

唯一索引使用 `owner_id + source + fingerprint`。同一持续事件更新最后观测时间，不创建重复自动执行。

### 8.3 incident_evidence

- `evidence_id`
- `event_id`
- `evidence_type`
- `source`
- `observed_at`
- `content_json`
- `content_hash`
- `trusted_level`

### 8.4 agent_run

- `run_id`
- `event_id`
- `model`
- `prompt_version`
- `input_evidence_ids`
- `structured_output`
- `token_usage`
- `latency_ms`
- `status`
- `error_code`

### 8.5 action_plan

- `plan_id`
- `event_id`
- `action_type`
- `target_service_id`
- `reason`
- `expected_result`
- `verification_window_seconds`
- `confidence`
- `risk_level`
- `rollback_type`
- `idempotency_key`
- `policy_result`

### 8.6 action_execution

- `execution_id`
- `plan_id`
- `idempotency_key`
- `status`
- `request_hash`
- `executor_request_id`
- `started_at`
- `completed_at`
- `result_json`
- `error_code`

`idempotency_key` 使用唯一索引。重复 RabbitMQ 消息读取已有结果并 ACK，不再次操作容器。

### 8.7 其他表

- `container_snapshot`：镜像、环境变量摘要、网络、挂载、端口、健康配置和运行状态。
- `outbox_event`：聚合 ID、事件类型、Payload、创建时间、发布时间和失败次数。
- `notification_record`：渠道、模板、状态、尝试次数和响应摘要。
- `audit_log`：主体、动作、资源、结果、关联事件、请求 ID 和时间。

所有表由 Flyway 管理。生产配置使用 `ddl-auto=validate`。

## 9. RabbitMQ 设计

交换机：

- `aiops.incident.exchange`：topic exchange。
- `aiops.incident.retry.exchange`：延迟重试路由。
- `aiops.incident.dead.exchange`：死信路由。

队列：

- `incident.diagnosis.queue`
- `incident.execution.queue`
- `incident.verification.queue`
- `incident.notification.queue`
- `incident.retry.30s.queue`
- `incident.retry.120s.queue`
- `incident.retry.600s.queue`
- `incident.dead.queue`

规则：

- 消息为持久化消息，队列为 durable。
- Publisher 使用 Confirm 和 mandatory return。
- Consumer 使用手动 ACK，数据库事务提交后确认。
- 消息只携带事件 ID、动作 ID、消息 ID、类型和版本，不携带完整日志或密钥。
- 处理语义是 at-least-once，幂等由数据库唯一键和状态版本保证。
- 30 秒、120 秒、600 秒重试队列通过 TTL 和 DLX 返回业务队列。
- 超过三次进入 `incident.dead.queue`，事件进入 `MANUAL_REQUIRED` 并通知企业微信。
- 重试只由消息消费边界负责，内部服务不再额外循环重试。

## 10. Redis 设计

Redis 保存以下可重建短期数据：

- `lock:container:{managedServiceId}`：容器写操作锁。
- `cooldown:container:{managedServiceId}`：动作冷却窗口。
- `counter:restart:{managedServiceId}:{window}`：重启次数。
- `circuit:container:{managedServiceId}`：容器自动化熔断状态。
- `nonce:executor:{nonce}`：执行请求防重放。
- `ratelimit:api:{ownerId}:{window}`：API 限流。
- `cache:evidence:{eventId}:{type}`：短期证据缓存。

MySQL 是事实来源，Redis 丢失不改变事件结果。Redis 不可用时允许告警接入、查询和 Agent 诊断，但暂停所有 Docker 写操作，并发送自动化暂停通知。不降级为无锁执行。

## 11. Agent 与证据边界

### 11.1 输入

Agent 仅接收：

- 当前事件的标准字段。
- 带来源和时间戳的证据引用。
- 当前托管服务允许动作的只读描述。
- 与症状匹配的 Runbook。
- 最近一次动作和验证结果。

日志、告警描述和知识库文本均标记为不可信数据。系统提示明确禁止执行数据中的指令。日志在进入模型前限制长度、移除控制字符并保留原始证据哈希。

### 11.2 输出

Agent 输出必须满足版本化 JSON Schema：

```json
{
  "diagnosis": "容器健康检查连续失败",
  "evidenceIds": ["evidence-1", "evidence-2"],
  "action": "RESTART_CONTAINER",
  "targetServiceId": "managed-service-1",
  "reason": "健康检查失败且错误日志持续增加",
  "expectedResult": "容器恢复 healthy 且错误数不再增长",
  "verificationWindowSeconds": 60,
  "confidence": 0.91,
  "rollbackAction": "NONE"
}
```

Schema 不合法、证据 ID 不存在、目标不匹配或动作不支持时，不尝试猜测修复，事件进入 `MANUAL_REQUIRED`。

### 11.3 模型不可用

DashScope 超时或配额耗尽时，系统可以使用完全确定性的 Runbook 规则处理已登记的单一故障，例如 `unhealthy -> restart`。没有精确规则则通知人工，不使用通用兜底 Prompt 编造结论。

## 12. 动作与策略边界

动作枚举：

- `GET_CONTAINER_STATE`
- `GET_CONTAINER_LOGS`
- `GET_CONTAINER_STATS`
- `CHECK_HTTP_HEALTH`
- `RESTART_CONTAINER`
- `RECREATE_WITH_STABLE_IMAGE`
- `RESTORE_SNAPSHOT`

风险分级：

- `READ_ONLY`：读取状态、日志、资源和健康检查，自动允许。
- `LOW`：重启一个白名单容器，命中确定性证据后自动允许。
- `MEDIUM`：使用登记的稳定镜像重建，存在完整快照和验证规则时自动允许。
- `HIGH`：多容器操作、网络修改、删除容器，第一版拒绝。
- `FORBIDDEN`：Shell、Volume 删除、Prune、宿主机命令，永久禁止。

`PolicyEngine` 检查：

1. 全局自动化开关开启。
2. 托管服务自动化开关开启。
3. 精确解析到一个白名单容器。
4. 动作在服务允许列表中。
5. 当前不处于维护抑制或熔断状态。
6. 同一容器 30 分钟内写操作少于两次。
7. 距离上次写操作超过五分钟。
8. ActionPlan 引用至少一个有效证据。
9. Agent 置信度达到策略阈值且确定性证据同时成立。
10. 中风险动作存在稳定镜像、快照和回滚条件。

系统自身的 MySQL、Redis、RabbitMQ、Milvus、`aiops-server` 和 `ops-executor` 永久加入保护名单。

## 13. 执行网关协议

内部接口：

- `GET /internal/v1/health`
- `POST /internal/v1/containers/inspect`
- `POST /internal/v1/containers/logs`
- `POST /internal/v1/containers/stats`
- `POST /internal/v1/actions/execute`

写请求头：

- `X-Request-Id`
- `X-Action-Id`
- `X-Timestamp`
- `X-Nonce`
- `X-Body-SHA256`
- `X-Signature`

签名内容使用固定字段顺序和 UTF-8 编码。请求有效窗口为 60 秒。Nonce 在 Redis 中保留 10 分钟。执行网关按以下顺序处理：

```text
请求大小和 JSON 结构
-> HMAC 签名
-> 时间窗口
-> Nonce
-> 动作枚举
-> 容器精确匹配
-> 执行器保护名单
-> 幂等请求 ID
-> Docker API
```

控制面的 PolicyEngine 负责业务规则；执行面的检查只负责确认调用方没有请求超出执行器能力。两者职责不同，不在其他层再次复制。

## 14. 自动修复场景

### 14.1 容器 unhealthy

- 证据：Docker Health 连续失败，HTTP 探针失败。
- 动作：`RESTART_CONTAINER`。
- 验证：容器 running、Health healthy、HTTP 2xx。
- 失败：一次延迟重试后熔断并通知人工。

### 14.2 服务不可访问

- 证据：HTTP 探针失败且容器仍运行，关键错误日志增加。
- 动作：重启白名单容器。
- 验证：探针恢复且验证窗口无新增关键错误。
- 失败：若配置稳定镜像则进入稳定镜像重建，否则人工接管。

### 14.3 OOM 或内存持续超限

- 证据：OOM 事件或内存连续多个窗口超过阈值。
- 动作：重启容器。
- 验证：内存回落且无新增 OOM。
- 失败：熔断，禁止循环重启。

### 14.4 新版本持续崩溃

- 证据：启动后反复退出、当前镜像不同于登记稳定镜像。
- 动作：保存快照并执行 `RECREATE_WITH_STABLE_IMAGE`。
- 验证：运行稳定、健康探针成功、错误不再增长。
- 失败：尝试 `RESTORE_SNAPSHOT`；仍失败则人工接管。

## 15. 验证、回滚和终态

容器进入 running 不等于故障恢复。`VerificationEngine` 根据 ActionPlan 和托管服务策略验证：

- Docker 状态为 running。
- Docker Health 为 healthy。
- HTTP 健康探针返回 2xx。
- 验证窗口内无新增指定关键错误。
- CPU 或内存回到配置阈值内。
- 原始告警不再持续 firing。

全部满足才进入 `RESOLVED`。部分满足不做模糊成功，进入重试、回滚或人工接管。

重启没有反向动作，因此失败后只允许执行一个已配置备用方案。重建动作执行前保存容器快照；稳定镜像重建失败时使用快照恢复原配置。快照缺失或快照恢复失败时立即熔断。

## 16. 故障处理

故障分类：

- `TEMPORARY`：模型超时、RabbitMQ 短暂断开、企业微信限流，进入 30/120/600 秒重试。
- `POLICY_DENIED`：目标或动作不允许，直接人工接管，不重试。
- `EXECUTION_FAILED`：Docker 动作失败，最多重试一次写操作。
- `VERIFICATION_FAILED`：动作成功但业务未恢复，执行一个备用动作或回滚。
- `NON_RECOVERABLE`：快照、稳定镜像或核心依赖缺失，立即熔断。

关键依赖降级：

- MySQL 不可用：Webhook 返回 503，依赖上游重试，不在内存中假装接收成功。
- RabbitMQ 不可用：Outbox 保留事件，恢复后补发。
- Redis 不可用：允许读和诊断，停止 Docker 写操作。
- DashScope 不可用：仅运行精确 Runbook；无规则则人工接管。
- ops-executor 不可用：进入延迟重试，禁止控制面直接访问 Docker。
- 企业微信不可用：通知进入重试和死信，不改变事件真实终态。

## 17. API 边界

外部 API 使用类型化 DTO 和统一 Problem Details，不返回 HTTP 200 携带业务 500。

主要 API：

- `POST /api/v1/incidents/webhooks/alertmanager`
- `POST /api/v1/incidents/webhooks/generic`
- `GET /api/v1/incidents`
- `GET /api/v1/incidents/{eventId}`
- `POST /api/v1/incidents/{eventId}/retry`
- `POST /api/v1/incidents/{eventId}/suppress`
- `GET /api/v1/managed-services`
- `POST /api/v1/managed-services`
- `PUT /api/v1/managed-services/{id}`
- `POST /api/v1/managed-services/{id}/automation/enable`
- `POST /api/v1/managed-services/{id}/automation/disable`
- `GET /api/v1/tasks/dead-letters`
- `POST /api/v1/tasks/dead-letters/{messageId}/requeue`
- `GET /api/v1/metrics/overview`

第一版继续使用 API Key，但默认启用安全配置。Key 仅保存哈希，不允许通过 URL 查询参数传递。CORS 使用显式来源列表。

## 18. 前端设计范围

Vue 控制台拆为：

- 总览：活跃事件、自动修复率、MTTA、MTTR、人工接管率和失败率。
- 事件中心：按状态、级别、服务和时间筛选。
- 事件详情：证据、Agent 诊断、ActionPlan、策略结果、执行和验证时间线。
- 托管服务：容器映射、稳定镜像、动作白名单、阈值和自动化开关。
- 策略中心：全局开关、冷却、重启上限和熔断状态。
- 任务中心：RabbitMQ 重试任务、死信任务和失败原因。
- 知识库：Runbook 和事故复盘。
- Agent 对话：作为事件详情中的辅助面板，而不是主页面。

前端不显示固定在线状态。所有 KPI 来自后端真实聚合接口。API Key 不写入 URL，默认只保存在当前浏览器会话；生产部署建议由反向代理注入身份。

## 19. 可观测性

引入 Spring Boot Actuator、Micrometer 和结构化日志，至少采集：

- `aiops.incident.received.total`
- `aiops.incident.resolved.total`
- `aiops.incident.manual.total`
- `aiops.action.executed.total`
- `aiops.action.failed.total`
- `aiops.action.duration`
- `aiops.agent.duration`
- `aiops.agent.token.usage`
- `aiops.queue.dead.total`
- `aiops.mtta`
- `aiops.mttr`

日志统一包含 `requestId`、`eventId`、`runId`、`planId` 和 `executionId`。Prompt 和完整日志默认不写入应用日志，避免敏感信息泄露。

## 20. Docker Compose 与端口

默认容器：

- `aiops-server`
- `ops-executor`
- `mysql`
- `redis`
- `rabbitmq`
- `milvus`
- `etcd`
- `minio`
- `prometheus`
- `alertmanager`
- `demo-service`（demo Profile）

宿主机端口：

- AIOps API：`9910`
- MySQL：`33306`
- Redis：`6380`
- Milvus：`19540`
- Etcd：`23379`
- MinIO API：`19100`
- MinIO Console：`19101`
- Milvus Metrics：`19091`
- RabbitMQ 管理台：`35672`（可选）
- Prometheus：`39090`
- Alertmanager：`39093`
- Demo Service：`39990`（可选）

RabbitMQ AMQP、`ops-executor` 和服务间通信默认只使用 Docker 内部网络，不映射宿主机端口。所有端口可通过独立环境变量覆盖。

## 21. 测试策略

### 21.1 单元测试

- 状态机合法和非法迁移。
- 告警标准化和指纹。
- PolicyEngine 动作允许与拒绝。
- ActionPlan JSON Schema。
- HMAC 规范和错误码映射。
- 验证结果判断。
- 重试分类和熔断条件。

### 21.2 集成测试

使用 Testcontainers 启动 MySQL、Redis、RabbitMQ 和专用 Docker 目标容器，验证：

- Flyway 从空库完整迁移。
- Outbox 发布和补发。
- RabbitMQ 重复投递不会重复执行。
- Redis 锁和冷却窗口。
- 未签名、过期和重放请求被拒绝。
- 白名单容器可重启，非白名单容器被拒绝。
- 修复成功、验证失败、回滚失败和死信路径。

测试只允许操作带随机前缀和 `aiops.test=true` Label 的容器。任何不匹配测试 Label 的写请求必须失败。

### 21.3 Agent 契约测试

CI 使用 Fake ChatModel 返回固定结构化结果，不需要真实 DashScope Key。测试证据引用、Schema 校验、策略输入和错误输出，避免把模型随机性当作测试条件。

### 21.4 前端和端到端测试

- Vue 组件测试覆盖事件列表、状态、时间线和错误展示。
- 端到端测试覆盖从 Demo 故障到自动恢复的完整路径。
- CI 使用 Mock 企业微信 Webhook，不向真实群发送消息。

## 22. CI

GitHub Actions 执行：

1. Maven 单元测试。
2. Maven 集成测试。
3. 前端依赖安装和构建。
4. Docker 镜像构建。
5. Docker Compose Demo 冒烟测试。
6. 凭证和 `.env` 文件检查。

CI 不要求真实 DashScope、腾讯 CLS 或企业微信凭证。真实外部集成作为本地验收项单独执行。

## 23. 明确不做

- Kubernetes。
- Kafka。
- SSH 远程主机。
- 多宿主机执行器。
- 任意 Shell 和宿主机命令。
- Docker Volume 自动删除。
- `docker system prune`。
- 自动修改代码、提交 Git 或发布版本。
- 自动修改业务数据库数据。
- 不受约束的工具自发现和自扩展。
- 生产级多租户 RBAC。
- 为未来场景预建空实现。

## 24. 分阶段实施

### 阶段一：现有链路校正

修复告警 Prompt 未传入、报告即解决、HTTP 200 错误响应和凭证安全问题；删除 Mock 静默降级。该阶段完成后项目仍是诊断系统，但结论可信。

### 阶段二：工程和消息基础

转换 Maven 多模块，引入 Flyway、状态机、Outbox、RabbitMQ 和 Redis。该阶段完成后告警任务可持久化、重试和恢复。

### 阶段三：独立执行网关

实现 `ops-contract`、HMAC 协议、只读 Docker 能力、动作白名单和容器保护。随后开放单容器重启。该阶段完成后具备最小安全自动修复。

### 阶段四：验证、回滚和熔断

加入延迟验证、稳定镜像重建、快照恢复、冷却和熔断。该阶段完成后形成真正闭环。

### 阶段五：真实演示环境

加入 Demo Service、Prometheus 和 Alertmanager 告警规则，形成可重复故障演示。

### 阶段六：Vue 事件控制台

删除旧静态 UI，完成总览、事件、服务、策略、任务、知识库和辅助对话页面。

### 阶段七：测试、CI 和简历材料

补齐自动测试、CI、架构图、演示视频、性能和可靠性指标，再生成最终简历描述。

每个阶段独立可运行和提交，不依赖后续阶段才能产生价值。

## 25. 验收标准

1. 同一告警重复发送只产生一个活动事件。
2. Agent 报告引用当前事件的真实证据，不再使用固定任务替代告警上下文。
3. RabbitMQ 重复投递不会重复重启容器。
4. 服务重启后可以恢复未完成事件。
5. 未签名、过期、篡改和重放的执行请求被拒绝。
6. 非白名单、多个匹配和保护名单容器无法被操作。
7. Demo Service unhealthy 可以自动诊断、重启、验证并进入 `RESOLVED`。
8. 错误版本可以使用登记稳定镜像重建并验证。
9. 修复失败会回滚或熔断，不会无限循环。
10. RabbitMQ 恢复后 Outbox 消息继续发送。
11. Redis 故障时 Docker 写操作自动暂停。
12. 企业微信收到事件、动作、恢复或人工接管结果。
13. Vue 展示完整证据和执行时间线，KPI 来自真实数据。
14. Maven、前端、集成测试和 CI 全部通过。

## 26. 回退策略

每个阶段使用独立提交。功能通过配置开关逐步启用：

- `AUTOMATION_ENABLED`
- `DOCKER_EXECUTION_ENABLED`
- `RABBITMQ_DISPATCH_ENABLED`
- `DEMO_FAULT_INJECTION_ENABLED`

关闭 Docker 执行后，系统退化为诊断、事件管理和通知平台，不影响告警接入。关闭 RabbitMQ 调度时不允许回退到进程内线程池执行写操作；未处理任务保留在 MySQL Outbox，等待恢复。

数据库迁移只增加新表和新字段，旧字段删除安排在完成数据迁移并经过一个稳定阶段后单独执行。

## 27. 简历证据

实施完成后采集而不是虚构以下数据：

- 告警接入到开始诊断的 P50/P95 时间。
- 自动修复成功率。
- MTTA 和 MTTR。
- 重复消息拦截数量。
- 自动回滚和人工接管比例。
- Agent 工具调用成功率、耗时和 Token 用量。
- 测试数量、覆盖的故障路径和 CI 时长。

简历描述以实测结果为准，核心叙事为：使用 Java 构建受策略约束的全自动 Docker 运维 Agent，通过 MySQL Outbox、RabbitMQ、Redis、独立执行网关、状态机和恢复验证实现可靠的告警到修复闭环。

## 28. 关键假设

本设计依赖 Docker 宿主机允许 `ops-executor` 挂载 Docker Socket。如果目标环境禁止该能力，执行面不可用，但告警、诊断、RabbitMQ、前端和通知仍然正常工作。系统不得通过挂载 Socket 到控制面或执行宿主机 Shell 来绕过限制。
