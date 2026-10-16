# Planner–Executor 运维主链路

## 本次改造边界

旧的自动修复链路由 Java 固定推进“采集、诊断、执行、验证”。新的主链路由 Planner 根据告警和实际观察选择下一步，由 Executor Agent 调用当前允许的工具，再由 Planner 重规划。不是仅在固定工作流失败后生成一份报告。

`AutonomousIncidentOrchestrator` 只负责事故生命周期、服务租约和通知。`PlannerExecutorRunner` 使用现有 Spring AI Alibaba 1.1.0.0-RC2 `StateGraph` 运行两个 `ReactAgent`。Planner 无执行工具，Executor 每轮只获得一个绑定到当前服务的工具；操作参数与目标不由 Executor 重新决定。

`ops-executor` 仍是独立 Docker 操作后端，不是第三个 Agent。未开放任意 Shell、代码修改、Dockerfile 生成、未知镜像构建或数据库写操作。

## 三种入口，一套处置核心

1. 业务 Webhook、Alertmanager 或健康巡检创建事故，继续通过既有事务消息进入 RabbitMQ。
2. `POST /api/v2/agent/runs` 人工发起调查，进入同一事故队列。
3. 聊天 Agent 的 `startOpsInvestigation` 和 `getOpsInvestigation` 分别创建、查询同一事故。聊天模型不直接获得 Docker 工具。

`POST /api/ai_ops` 已调整为兼容的任务创建入口，不再启动独立 Supervisor 报告图，也不再返回模拟流式报告。它需要与新建任务相同的 JSON 请求体。开启旧版 URL 时，`/api/incident/webhook` 仅桥接统一的事故接入，使用标准 `IncidentIngestRequest`；旧版轮询报告服务已移除。第三方应使用 `/api/v2/incidents` 和现有 Alertmanager 接口，不依赖旧版事件表。

## 发起调查

```http
POST /api/v2/agent/runs
Content-Type: application/json
X-API-Key: <控制面配置的密钥；本地关闭鉴权时可省略>

{
  "serviceId": "demo-service",
  "task": "检查服务健康异常的原因",
  "requestId": "operator-investigation-001",
  "allowRemediation": false
}
```

返回 HTTP 202 与 `incidentId`，表示已受理，不代表调查完成或已经修复。相同逻辑请求应复用 `requestId`。服务标识必须与托管服务注册信息一致；不创建用户没有注册的目标。

`allowRemediation` 默认为 false。设为 true 只是允许提出受控处置，仍然要满足服务自动化配置、已批准 Runbook、白名单、置信阈值、真实证据、服务锁、冷却和频率限制。

```http
GET /api/v2/agent/runs/<incidentId>
X-API-Key: <控制面密钥>
```

返回真实事故状态、公开步骤、验证标记和最终记录。原有 `/api/v2/incidents/<incidentId>` 继续提供关联告警、完整时间线和操作记录。

## 对话使用

Vue 控制台增加运维助手面板，沿用现有界面样式。先选择已注册服务，再输入调查问题。“直接发起调查”确定性创建任务，不依赖模型是否正确提出工具调用；“发送问题”调用现有实时聊天接口，由聊天 Agent 按需发起或查询任务。普通技术问答不应创建事故。

通过“允许本次请求发起受控修复”复选框明确授予本次写权限。该值和 HTTP 密钥在后端绑定，不作为模型可以填写的工具参数。关闭接收聊天流不等于取消已经入队的运维任务。

API 调用聊天时，使用 `/api/chat_stream`，请求体 `Mode=agent`，`AllowOperations=false` 为默认值。控制面开启鉴权时必须提供其真实 `X-API-Key`，即使普通聊天入口另有鉴权，也不能绕过控制面检查。

## 当前工具

| 工具 | 实际能力 |
|---|---|
| getContainerState / getContainerLogs / getContainerStats | 通过执行服务查询已注册容器；日志最多 300 行，提供给模型的长文本有截断提示 |
| checkHttpHealth | 查询健康接口；不自动代表已经完成修复验证 |
| searchRunbooks | 从 MySQL 查询并匹配已批准处置方案，返回真实方案 ID |
| searchKnowledge | 复用现有 Milvus 文档检索；文档内容不是执行授权 |
| queryMetrics | 查询现有 Prometheus 活跃告警工具，不声称支持任意 PromQL 或所有中间件指标 |
| restartContainer / recreateWithStableImage | 经策略校验后操作当前服务；稳定镜像重建不是自动构建新镜像 |
| verifyRecovery | 通过代码检查容器运行状态与 HTTP 健康；FINISH 必须有该验证通过记录 |
| rollback | 恢复当前事故已记录的重建快照；不接受模型指定的任意容器或快照 |

## 状态、预算与恢复

事故诊断 JSON 保存 `AgentCheckpoint`，包括计划、实际观察、已完成轮次、工具次数、验证标记和最终记录。计划在执行前保存；同一步的操作使用事故 ID、轮次和工具名构成稳定请求标识。数据库动作记录及执行器日志继续负责动作幂等。

已完成动作的结果先从动作记录恢复，不再作为新动作重新经过冷却判定或再次计数；仍需执行真实恢复验证。执行器结果为 UNKNOWN 时转人工，不因恢复任务而当作成功。

每段默认 8 轮，总预算默认 32 轮、40 次业务工具调用、600 秒任务时间。每次 Planner 最多 1 次模型调用，Executor 最多 3 次模型调用且仅执行一个绑定工具，使用框架原生限制 Hook。模型调用等待默认最多 60 秒。预算在程序中检查，不相信模型自行填写计数。

分段达到预算时保存检查点，通过既有 Outbox 排队恢复；总预算耗尽、连续三步相同结果或无法继续时转人工，不伪造成功。模型请求取消不能保证远程已经发出的 Docker 命令被撤销；执行结果不确定时必须人工核验，不能改一个请求编号盲目重试。

没有配置模型或模型输出不合法时，不再使用“模拟诊断置信度”自动执行写操作。恢复判定由代码掌握，不由模型的一句“已修复”决定。重建后的安全退出保留快照补偿和人工升级；补偿失败也必须留下记录。

时间线记录的是公开计划和实际步骤，不是完整内部思维链。最终记录由真实观察整理，Planner 的结论不能替代操作记录与健康验证。

## 定向验证

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh"
mvn -pl aiops-server,ops-executor -am \
  -Dtest=AgentPlanTest,AgentCheckpointTest,PlannerExecutorRunnerTest,OpsConversationToolsTest,IncidentStateMachineTest,AutomationPolicyEngineTest,ExecutorActionJournalTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
cd frontend && npm run build
```

框架集成测试使用脚本化 ChatModel 驱动真实 ReactAgent 与 StateGraph；网络、模型供应商和仓库是测试边界。该结果不能当作真实模型诊断准确率、生产恢复率或并发性能。

真实运维联调需要本项目 MySQL、RabbitMQ、Redis、Server、ops-executor 与演练服务可用。Prometheus 和 Milvus 只影响对应查询能力，不影响所有工具；模型能否改换调查方向仍需要真实模型验证。复用 AIOps 专属容器和网络，不连接其他项目中间件，不打印 `.env` 的模型或邮箱密钥。

## 2026-10-16 真实模型联调记录

本轮使用 SDKMAN 构建的本地 Server、现有 AIOps MySQL / Redis / RabbitMQ、独立执行器和演练服务。测试使用独立 MySQL 库及 RabbitMQ 虚拟主机，未消费主队列中的历史消息。没有创建重复的中间件容器，也没有连接社区项目容器。

1. 真实模型只读调查：实际查询容器状态与健康接口。模型两次提前提出 FINISH，完成检查均拒绝；实际调用 `verifyRecovery` 并通过后结案，没有 Docker 写操作。
2. 真实故障修复：向演练服务注入健康故障，接口返回 HTTP 503。模型自主选择 `searchRunbooks → checkHttpHealth → getContainerState → getContainerLogs → restartContainer → verifyRecovery`，动作表记录一次成功重启，HTTP 恢复 200 后事故变为 RESOLVED。共 7 次规划，约 27 秒完成。这是单次本地演练结果，不是恢复率或性能承诺。
3. 聊天查询：通过真实聊天模型调用事故查询工具，返回同一事故的状态、步骤与最终记录，SSE 正常结束。
4. 聊天发起：通过本地 Vite 代理调用聊天接口，创建只读事故并进入同一队列。后台实际验证通过后结案，动作表为空，未出现无快照却记录清理失败的问题。
5. 告警聚合前置改动在本项目 MySQL 的临时独立库验证，包括重复事件和同类告警并发关联；未清空应用数据库。
6. 最终构建再次注入 HTTP 503 后，模型选择了不同顺序：`checkHttpHealth → getContainerState → getContainerLogs → searchRunbooks → restartContainer → verifyRecovery`。实际仍仅重启一次并验证 HTTP 200，说明工具顺序不是 Java 中写死的固定检查清单。浏览器检查确认运维助手、服务选项、事故状态及处理时间线正常加载。

脚本化框架测试还覆盖了未检索方案禁止写、只读任务禁止写、模型不调用工具、预算截止、分段恢复、已完成动作结果恢复、重建失败补偿、回滚和 UNKNOWN 结果转人工。真实演练未覆盖所有模型、所有故障、稳定镜像重建和真实邮件投递，也未重新压测，不能据此新增并发指标。

### 本地启动注意事项

- Vite 默认将 `/api` 代理到 `http://127.0.0.1:29900`；后端端口不同可通过 `VITE_API_PROXY_TARGET` 指定。前端继续在本机运行，不放入容器。
- `autonomous` 配置通过 Flyway 建表，本次新增 V203 为全新数据库补齐聊天会话与消息表，不依赖 Hibernate 自动建表。
- Spring AI Alibaba RC2 的图序列化使用 Jackson 新接口，根 POM 统一到 Jackson BOM 2.19.1，避免运行时 `NoSuchMethodError`，没有更换 Graph 框架。
- 项目移动目录后，执行器目标配置与 Prometheus 的旧绑定挂载失效。本轮只重建这两个服务以重新解析当前路径，保留既有网络、数据卷和端口。实际 Compose 项目为 `aiops-autonomous`，网络为 `aiops-autonomous_default`。
- 当前本地环境文件为 `.env.aiops`；操作 Compose 时同时指定 `--env-file .env.aiops` 和 `AIOPS_ENV_FILE=.env.aiops`，避免默认环境文件名不一致。不要在命令输出、文档或提交中写出密钥。
- 主队列检查时留有 8,851 条历史消息；本轮未清空，也未启动主 Server 消费。恢复主环境前应先确认这些事件是否仍需处理，避免集中调用模型或重复通知。
- 本轮隔离测试沿用的 SMTP 配置返回认证失败，测试关闭了邮件通知及邮件健康检查。整体核心链路健康通过不等于邮件投递通过；现有邮箱配置未修改，需要单独确认有效授权码及发信配置。
