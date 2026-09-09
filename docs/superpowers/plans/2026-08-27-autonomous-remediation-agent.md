# 自主运维 Agent 风险分级与动态工具改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**目标：** 将现有固定动作空间的自动运维链路升级为可调查未知故障、生成新工具、经过多 Agent 审核和风险分级后执行，并能持续沉淀处置能力的自主运维系统。

**架构：** 保留当前状态机、策略引擎、RabbitMQ、Redis、独立执行器、验证回滚和邮件通知链路。新增调查 Agent、处置提案、审核 Agent、确定性风险计算、邮件审批、动态工具沙箱和工具晋升机制。LLM 负责调查、推理、生成候选方案和审核意见，确定性系统负责风险计算、授权、执行、验证、回滚和审计。

**技术栈：** Java、Spring Boot、Spring AI、Spring AI Alibaba Graph、MySQL、Redis、RabbitMQ、Docker Compose、HMAC、Vue。

## 全局约束

- 继续使用 Java 作为控制面和执行面的主要开发语言，不新增 Python 常驻服务。
- 不允许 LLM 直接生成并在生产宿主机执行任意 Shell 命令。
- 自动执行必须经过审核 Agent 和确定性 Policy Engine，两者任一拒绝都不得执行。
- 风险分数不超过 6 分只代表具备自动执行资格，仍须满足动作白名单、置信度、幂等、回滚和验证条件。
- 7 至 8 分的动作必须通过邮件审批，9 至 10 分的动作禁止 Agent 执行。
- 新生成的只读工具可以在沙箱验证后自动运行；会修改生产状态的新工具首次执行必须人工批准。
- 动态工具不得直接挂载宿主机目录、Docker Socket、SSH 私钥、应用密钥或生产数据库凭证。
- 沙箱服务只加入 Docker 内部网络，不映射宿主机端口，避免与现有容器端口冲突。
- 每次执行必须有唯一幂等键、超时、执行次数限制、冷却时间、审计记录和明确终态。
- 每个实施阶段可以单独关闭，不影响现有固定动作自动运维链路。

---

## 1. 当前状态和目标边界

### 1.1 当前能力

现有链路如下：

~~~text
告警接入
→ 事件持久化
→ RabbitMQ 异步调度
→ 固定证据收集
→ LLM 结构化诊断
→ Runbook 匹配
→ Policy Engine 决策
→ 独立 Executor 执行动作
→ 健康检查验证
→ 回滚或邮件通知
~~~

当前系统的诊断范围比执行范围更宽。LLM 可以分析未预设的故障，但执行器只能运行 ActionType 中已经登记并实现的动作。没有匹配动作时，系统只能停止自动处置并通知人工。

### 1.2 目标能力

1. 调查 Agent 根据中间证据动态选择只读工具，不再局限于一次固定证据收集。
2. 调查 Agent 同时维护多个故障假设，并根据新证据更新置信度。
3. 运维 Agent 生成结构化处置提案，不直接获得生产执行权限。
4. 审核 Agent 独立读取原始证据，评价诊断质量、动作风险、验证方案和回滚方案。
5. Policy Engine 使用固定公式重新计算风险，不直接信任 LLM 给出的总分。
6. 低风险动作自动执行，中风险动作自动金丝雀执行，高风险动作邮件审批，禁止动作直接拒绝。
7. 当现有工具不足时，Tool Builder Agent 可以生成新的诊断或处置工具。
8. 新工具先在隔离 Docker 沙箱中运行和测试，不直接进入生产执行器。
9. 工具经过首次人工批准和多次成功验证后，才能晋升为可信工具。
10. 调查、审核、审批、执行、验证和回滚均写入事故时间线。

### 1.3 不在本计划范围内

- 不承诺解决所有未知生产故障。
- 不开放 Root Shell、任意 SSH、任意 SQL 和任意 Docker API。
- 不让审核 Agent 成为唯一安全边界。
- 不允许两个 Agent 无限制辩论，单个提案最多允许两轮补证。
- 不自动执行删除数据、删除存储卷、修改数据库结构等不可逆动作。
- 第一阶段不引入 Kubernetes、Firecracker 或新的云平台。

---

## 2. 目标架构

~~~text
Alertmanager / 接入 API
          ↓
Incident Orchestrator
          ↓
Investigation Agent
动态选择只读工具、维护故障假设
          ↓
Remediation Agent
生成结构化处置提案
          ↓
现有工具能否满足？
    ├── 能：进入审核
    └── 不能：Tool Builder Agent
                    ↓
              Tool Sandbox Runner
              隔离运行、测试、生成报告
                    ↓
Review Agent
独立读取原始证据、工具清单和沙箱报告
          ↓
Deterministic Policy Engine
重新计算风险、执行硬规则
          ↓
    ├── 0 至 3 分：自动执行
    ├── 3.01 至 6 分：金丝雀执行
    ├── 6.01 至 8 分：邮件审批
    └── 8.01 至 10 分：拒绝执行
          ↓
Independent Executor
          ↓
Independent Verifier
    ├── 成功：关闭事故并沉淀工具
    └── 失败：回滚、撤销工具、邮件通知
~~~

### 2.1 信任边界

| 组件 | 可读生产证据 | 可修改生产 | 可建议批准 | 最终裁决 |
|---|---:|---:|---:|---:|
| Investigation Agent | 是，只读接口 | 否 | 否 | 否 |
| Remediation Agent | 是，只读接口 | 否 | 否 | 否 |
| Tool Builder Agent | 否，只使用脱敏样本 | 否 | 否 | 否 |
| Review Agent | 是，只读接口 | 否 | 是 | 否 |
| Policy Engine | 读取结构化数据 | 否 | 否 | 是 |
| Approval Service | 读取提案摘要 | 否 | 记录人工决定 | 否 |
| Executor | 读取受限上下文 | 是，固定能力 | 否 | 否 |
| Verifier | 是，只读接口 | 仅请求回滚 | 否 | 否 |

审核 Agent 与运维 Agent 应使用不同系统提示词和独立上下文。审核 Agent 不读取运维 Agent 的隐藏推理，只读取原始证据、结构化提案、工具清单和沙箱报告。

---

## 3. Agent 职责和协议

### 3.1 Investigation Agent

输入包括事故记录、服务配置、初始告警和已有证据。每一轮只能执行一种结果：

~~~text
CALL_TOOL：调用一个只读工具
COMPLETE：证据足够，结束调查
ESCALATE：需要人工业务判断或现有证据不足
~~~

建议接口：

~~~java
public interface InvestigationAgent {
    InvestigationStep investigate(InvestigationContext context);
}

public record InvestigationStep(
        InvestigationDecision decision,
        List<IncidentHypothesis> hypotheses,
        ToolInvocationRequest nextTool,
        String rationale) {
}
~~~

允许的首批只读工具：

- 容器状态和重启次数查询。
- 指定服务日志查询。
- HTTP 健康检查。
- JVM、Redis、RabbitMQ、MySQL 状态查询。
- 最近部署记录和服务版本查询。
- Runbook 和历史事故检索。

终止条件：

- 至少一个故障假设达到诊断置信度阈值。
- 达到 8 轮调查上限。
- 达到 5 分钟执行上限。
- 所有可用工具都无法提供新的有效证据。
- 需要读取敏感业务数据或进行人工业务判断。

### 3.2 Remediation Agent

Remediation Agent 只能生成提案，不能调用生产写工具。提案绑定证据版本和不可变哈希：

~~~java
public record RemediationProposal(
        UUID proposalId,
        UUID incidentId,
        long evidenceVersion,
        String rootCause,
        double diagnosisConfidence,
        ActionSpecification action,
        int incidentSeverity,
        String blastRadius,
        List<VerificationCriterion> verification,
        RollbackSpecification rollback,
        Instant expiresAt,
        String proposalHash) {
}
~~~

ActionSpecification 必须引用现有动作目录或动态工具的固定版本，不允许只保存自由文本命令。

### 3.3 Review Agent

审核结果仅允许：

~~~text
APPROVE
REJECT
NEED_EVIDENCE
ESCALATE
~~~

建议接口：

~~~java
public interface RemediationReviewAgent {
    ReviewAssessment review(ReviewContext context);
}

public record ReviewAssessment(
        ReviewDecision decision,
        RiskFactors riskFactors,
        List<String> evidenceReferences,
        List<String> missingEvidence,
        String reason) {
}
~~~

NEED_EVIDENCE 会让状态机回到调查阶段。相同提案最多补证两轮，超过后自动升级人工。

### 3.4 Tool Builder Agent

Tool Builder Agent 仅在现有能力不足时启动，每次必须同时生成：

1. 工具源码。
2. ToolManifest。
3. 正常、异常和边界测试样例。
4. 验证方案。
5. 回滚方案或无副作用声明。

第一版只允许生成 Bash 工具，并且只能在专用沙箱中执行。Java 动态工具后续以独立 JAR 制品接入，不在控制面运行时动态加载类。

---

## 4. 风险评分和决策

### 4.1 三个独立指标

- incidentSeverity，事故严重程度，影响告警优先级和响应时限。
- actionRiskScore，处置动作风险，决定执行权限。
- diagnosisConfidence，诊断置信度，判断证据是否足以支持动作。

事故越严重不代表动作越危险。核心服务宕机时，重启单个无状态实例可能是低风险动作；普通磁盘告警下，删除未知文件仍是高风险动作。

### 4.2 风险因素

~~~java
public record RiskFactors(
        double blastRadius,
        double dataImpact,
        double irreversibility,
        double permissionLevel,
        double toolNovelty,
        double diagnosticUncertainty,
        double verificationUncertainty) {
}
~~~

每项范围为 0 至 10，数字越高风险越大。

~~~text
actionRiskScore =
    blastRadius             × 0.25
  + dataImpact              × 0.20
  + irreversibility         × 0.15
  + permissionLevel         × 0.15
  + toolNovelty             × 0.10
  + diagnosticUncertainty   × 0.10
  + verificationUncertainty × 0.05
~~~

Review Agent 可以建议各项分值。Policy Engine 必须依据动作目录中的固定元数据修正数据风险、权限、工具成熟度和可逆性，并将最终分数限制在 0 至 10。

### 4.3 决策区间

| 风险分数 | 决策 | 行为 |
|---:|---|---|
| 0.00 至 3.00 | AUTO_EXECUTE | 通过硬规则后自动执行 |
| 3.01 至 6.00 | AUTO_CANARY | 最小范围执行，验证成功后才能扩大 |
| 6.01 至 8.00 | REQUIRE_HUMAN | 发送邮件，等待限时人工审批 |
| 8.01 至 10.00 | DENY | 不允许 Agent 执行，只给人工建议 |

### 4.4 自动执行硬规则

风险不超过 6 分时仍须同时满足：

- Review Agent 明确批准。
- diagnosisConfidence 不低于 0.85。
- 动作声明幂等。
- 不删除业务数据、不修改数据库结构、不删除存储卷。
- 存在客观成功指标。
- 存在可执行回滚，或者动作被证明无副作用。
- 影响范围不超过单个服务。
- 不需要 Root 权限。
- Redis 租约、冷却、频率限制和熔断检查通过。
- 提案未过期，证据版本和参数哈希未变化。
- 同一事故没有其他正在运行的处置动作。

任一条件不满足，至少升级为人工审批。命中禁止规则时直接拒绝。

### 4.5 硬拒绝规则

- 递归删除未知目录。
- 未参数化的 SQL 写操作。
- 删除数据库、表、索引或 Docker Volume。
- 修改宿主机用户、权限、防火墙或 SSH 配置。
- 读取或传输未在清单中声明的密钥。
- 将生产数据发送给外部网络。
- 一次修改多个服务且没有金丝雀范围。
- 缺少工具清单、源码哈希或测试报告。

---

## 5. 动态工具模型

### 5.1 ToolManifest

~~~java
public record ToolManifest(
        UUID toolId,
        String name,
        int version,
        ToolKind kind,
        ToolLifecycleStatus status,
        ToolRiskMetadata risk,
        ToolPermissionManifest permissions,
        ToolExecutionManifest execution,
        List<VerificationCriterion> verification,
        RollbackSpecification rollback,
        String sourceSha256,
        Instant createdAt) {
}
~~~

示例：

~~~yaml
name: inspect-order-service-log-growth
version: 1
kind: READ_ONLY_DIAGNOSTIC
status: DRAFT
risk:
  modifiesState: false
  idempotent: true
  maxBlastRadius: SINGLE_SERVICE
permissions:
  filesystem: []
  networkTargets:
    - service-metrics-proxy:8080
  environmentVariables: []
execution:
  timeoutSeconds: 30
  cpuLimit: "0.50"
  memoryLimitMb: 128
  networkMode: INTERNAL_ONLY
verification:
  - type: EXIT_CODE
    expected: 0
rollback:
  required: false
~~~

### 5.2 工具生命周期

~~~text
DRAFT
→ SANDBOX_TESTED
→ REVIEWED
→ HUMAN_APPROVED
→ CANDIDATE
→ TRUSTED
→ AUTO_APPROVED
→ REVOKED
~~~

- DRAFT，Agent 刚生成，不能用于生产。
- SANDBOX_TESTED，静态检查、测试和沙箱运行通过。
- REVIEWED，审核 Agent 通过，仍没有生产授权。
- HUMAN_APPROVED，人工批准首次有副作用的生产执行。
- CANDIDATE，首次生产执行成功。
- TRUSTED，同一版本连续成功 3 次，并且没有触发回滚。
- AUTO_APPROVED，管理员允许该版本在风险不超过 6 分时自动运行。
- REVOKED，执行失败、环境变化、源码变化或人工禁用。

只读工具可以从 REVIEWED 进入 CANDIDATE。会修改生产状态的新工具不能跳过 HUMAN_APPROVED。

### 5.3 不可变工具制品

每个工具版本保存：

- 规范化源码。
- SHA-256 哈希。
- ToolManifest。
- 测试输入和预期输出。
- 沙箱执行日志。
- 审核记录。
- 人工审批记录。
- 生产执行成功、失败和回滚次数。

审批和生产执行绑定统一制品哈希。源码、清单或测试变化时，必须创建新版本并重新审核。

---

## 6. 沙箱执行

新增 tool-sandbox-runner 模块和内部 Docker 服务。该服务不持有生产凭证，也不访问宿主机 Docker API。

容器约束：

~~~yaml
read_only: true
cap_drop:
  - ALL
security_opt:
  - no-new-privileges:true
tmpfs:
  - /workspace:size=64m,nosuid
mem_limit: 256m
cpus: 0.5
pids_limit: 64
network_mode: none
~~~

第一版沙箱支持：

- Bash 语法检查。
- 命令令牌允许列表和禁止列表。
- 脚本最大 32 KB。
- 最长运行 30 秒。
- 标准输出和错误输出各限制 1 MB。
- 禁止后台进程、守护进程和提权命令。
- 每次执行使用新的临时目录。
- 执行结束清理文件和环境变量。

需要模拟 HTTP 服务时，使用专用 sandbox-net 内部网络，不映射宿主机端口。输入只包含脱敏日志、模拟指标、测试配置和预期结果。

沙箱通过只表示工具可以进入审核阶段，不代表获得生产权限。

---

## 7. 邮件审批

风险为 6.01 至 8.00，或命中强制人工条件时，邮件展示：

- 事故编号、服务、环境、时间和严重程度。
- 根因、诊断置信度和关键证据。
- 拟执行动作、目标、参数和影响范围。
- Review Agent 结论和各风险维度。
- 最终风险分数及人工审批原因。
- 验证和回滚方案。
- 审批有效期。
- 批准和拒绝按钮。

审批令牌绑定：

~~~text
incidentId
proposalId
proposalHash
actionType
target
parametersHash
expiresAt
nonce
~~~

令牌使用 HMAC 签名，默认 15 分钟过期，只允许使用一次。批准时重新检查事故状态、提案哈希、风险、Redis 租约、熔断和冷却状态。

审批状态：

- APPROVED，重新经过 Policy Engine 后执行。
- REJECTED，转入人工处理。
- EXPIRED，提案失效，重新调查后才能提交。
- INVALIDATED，证据、参数或服务版本变化导致原审批失效。

---

## 8. 状态机和事件

### 8.1 新增状态

~~~text
INVESTIGATING
PROPOSING
BUILDING_TOOL
SANDBOX_TESTING
REVIEWING
EVIDENCE_REQUIRED
AWAITING_APPROVAL
CANARY_EXECUTING
PROMOTING_TOOL
~~~

所有状态转换由 IncidentStateMachine 验证。Controller、Consumer 和 Agent 不能直接修改状态。

### 8.2 关键事件

~~~text
incident.investigation.requested
incident.evidence.collected
incident.proposal.created
incident.review.requested
incident.review.completed
incident.evidence.requested
incident.approval.requested
incident.approval.completed
incident.action.approved
incident.canary.requested
incident.verification.completed
tool.build.requested
tool.sandbox.completed
tool.promoted
tool.revoked
~~~

关键事件继续使用 Outbox 发布。Consumer 通过事件 ID 和业务幂等键去重。

---

## 9. 数据模型

新增表：

| 表 | 用途 |
|---|---|
| incident_hypothesis | 保存每轮故障假设、置信度和证据引用 |
| remediation_proposal | 保存不可变提案和提案哈希 |
| remediation_review | 保存审核决策、风险因素和补证要求 |
| approval_request | 保存审批状态、令牌摘要和有效期 |
| dynamic_tool | 保存工具身份、当前版本和生命周期 |
| dynamic_tool_version | 保存源码、清单、制品哈希和审核信息 |
| tool_sandbox_run | 保存沙箱输入摘要、输出和退出状态 |
| tool_execution_stats | 保存成功率、回滚率和最近执行结果 |

约束：

- 提案和工具版本不可原地修改，只能创建新版本。
- 审批表只保存令牌哈希，不保存原始令牌。
- 数据库保存证据引用和脱敏摘要，不重复复制全部日志。
- 时间使用 UTC 存储，展示时转换为 Asia/Shanghai。
- 事故、提案、审核、审批和执行记录使用不可变 UUID 关联。

---

## 10. API

控制面 API：

~~~text
GET  /api/incidents/{incidentId}/investigation
GET  /api/incidents/{incidentId}/proposals
GET  /api/incidents/{incidentId}/reviews
POST /api/approvals/{token}/approve
POST /api/approvals/{token}/reject
GET  /api/tools
GET  /api/tools/{toolId}/versions/{version}
POST /api/tools/{toolId}/revoke
POST /api/tools/{toolId}/promote
~~~

沙箱内部 API：

~~~text
POST /internal/sandbox/runs
GET  /internal/sandbox/runs/{runId}
~~~

沙箱接口只在 Docker 内部网络开放，通过 HMAC 和时间戳认证。

---

## 11. 前端范围

Vue 前端新增：

1. 事故调查时间线，展示假设、工具调用、证据和置信度变化。
2. 处置提案与审核面板，展示动作、风险维度、审核结果和补证过程。
3. 工具中心，展示版本、生命周期、沙箱结果、执行成功率和撤销状态。
4. 审批详情页，展示邮件中的核心信息并提供批准、拒绝操作。

前端不能修改风险评分、提案参数或工具源码。批准按钮提交不可变审批令牌，所有安全校验仍由服务端完成。

---

## 12. 可观测性

新增指标：

~~~text
aiops_investigation_rounds_total
aiops_investigation_duration_seconds
aiops_review_decisions_total
aiops_risk_score_distribution
aiops_approval_requests_total
aiops_approval_latency_seconds
aiops_dynamic_tools_total
aiops_tool_sandbox_runs_total
aiops_tool_production_success_rate
aiops_tool_rollbacks_total
aiops_incidents_auto_resolved_total
aiops_incidents_human_escalated_total
~~~

日志统一携带 incidentId、proposalId、reviewId、toolId、toolVersion、actionExecutionId 和 correlationId。系统需要能够从生产动作追溯到原始告警、证据、提案、审核、风险计算、审批、工具版本、执行日志和验证结果。

---

## 13. 分阶段实施

每个阶段都可以独立上线。后续阶段未实施时，系统继续使用现有固定动作链路。

### Task 1：处置提案、审核和风险领域模型

**创建：**

- aiops-server/src/main/java/org/example/incident/remediation/RemediationProposal.java
- aiops-server/src/main/java/org/example/incident/remediation/ActionSpecification.java
- aiops-server/src/main/java/org/example/incident/remediation/VerificationCriterion.java
- aiops-server/src/main/java/org/example/incident/remediation/RollbackSpecification.java
- aiops-server/src/main/java/org/example/incident/review/ReviewAssessment.java
- aiops-server/src/main/java/org/example/incident/review/RiskFactors.java
- aiops-server/src/main/java/org/example/incident/review/ReviewDecision.java
- aiops-server/src/main/java/org/example/incident/policy/RiskScoreCalculator.java

**修改：**

- aiops-server/src/main/java/org/example/incident/policy/AutomationPolicyEngine.java
- aiops-server/src/test/java/org/example/incident/policy/AutomationPolicyEngineTest.java

**新增测试：**

- aiops-server/src/test/java/org/example/incident/policy/RiskScoreCalculatorTest.java

**步骤：**

- [ ] 编写风险边界测试，覆盖 0、3、3.01、6、6.01、8、8.01 和 10。
- [ ] 编写数据删除、Root 权限、无回滚写操作的硬拒绝测试。
- [ ] 实现不可变提案、审核结果和风险因素对象。
- [ ] 实现确定性风险公式，忽略 LLM 输出的最终总分。
- [ ] 将风险决策接入现有 AutomationPolicyEngine，保持旧动作行为不变。
- [ ] 运行 mvn -pl aiops-server -Dtest=RiskScoreCalculatorTest,AutomationPolicyEngineTest test，预期全部通过。

### Task 2：动态调查 Agent

**创建：**

- aiops-server/src/main/java/org/example/incident/investigation/InvestigationAgent.java
- aiops-server/src/main/java/org/example/incident/investigation/InvestigationContext.java
- aiops-server/src/main/java/org/example/incident/investigation/InvestigationStep.java
- aiops-server/src/main/java/org/example/incident/investigation/IncidentHypothesis.java
- aiops-server/src/main/java/org/example/incident/investigation/ReadOnlyOpsToolRegistry.java

**修改：**

- aiops-server/src/main/java/org/example/incident/diagnosis/EvidenceCollector.java
- aiops-server/src/main/java/org/example/incident/diagnosis/IncidentDiagnosisEngine.java
- aiops-server/src/main/java/org/example/incident/application/AutonomousIncidentOrchestrator.java

**测试：**

- aiops-server/src/test/java/org/example/incident/investigation/InvestigationAgentTest.java

**步骤：**

- [ ] 编写工具白名单、轮次上限、时间上限和重复工具调用测试。
- [ ] 将现有 EvidenceCollector 包装成只读工具注册表。
- [ ] 使用 Spring AI 结构化输出实现调查步骤和假设更新。
- [ ] 在 Orchestrator 中加入调查循环，固定证据流程作为 LLM 不可用时的降级路径。
- [ ] 将每轮假设、工具请求和证据引用写入事故时间线。
- [ ] 运行 InvestigationAgentTest，预期全部通过。

### Task 3：审核 Agent 和补证闭环

**创建：**

- aiops-server/src/main/java/org/example/incident/review/RemediationReviewAgent.java
- aiops-server/src/main/java/org/example/incident/review/ReviewContext.java
- aiops-server/src/main/java/org/example/incident/remediation/RemediationProposalService.java

**修改：**

- aiops-server/src/main/java/org/example/incident/application/AutonomousIncidentOrchestrator.java
- aiops-server/src/main/java/org/example/incident/domain/IncidentStateMachine.java
- aiops-server/src/main/java/org/example/incident/domain/IncidentStatus.java
- aiops-server/src/test/java/org/example/incident/domain/IncidentStateMachineTest.java

**测试：**

- aiops-server/src/test/java/org/example/incident/review/RemediationReviewAgentTest.java

**步骤：**

- [ ] 编写非法审核输出、补证和补证轮次上限测试。
- [ ] 实现提案服务，生成提案哈希并绑定证据版本。
- [ ] 实现独立审核上下文。
- [ ] 加入 PROPOSING、REVIEWING 和 EVIDENCE_REQUIRED 状态。
- [ ] 将批准结果送入 Policy Engine，将拒绝和升级结果写入时间线并通知人工。
- [ ] 运行审核和状态机测试，预期全部通过。

### Task 4：邮件审批和一次性授权

**创建：**

- aiops-server/src/main/java/org/example/incident/approval/ApprovalRequest.java
- aiops-server/src/main/java/org/example/incident/approval/ApprovalService.java
- aiops-server/src/main/java/org/example/incident/approval/ApprovalTokenSigner.java
- aiops-server/src/main/java/org/example/incident/api/ApprovalController.java

**修改：**

- aiops-server/src/main/java/org/example/incident/notification/MailIncidentNotifier.java
- aiops-server/src/main/java/org/example/incident/application/AutonomousIncidentOrchestrator.java

**测试：**

- aiops-server/src/test/java/org/example/incident/approval/ApprovalServiceTest.java
- aiops-server/src/test/java/org/example/incident/approval/ApprovalTokenSignerTest.java

**步骤：**

- [ ] 编写令牌篡改、过期、重复使用、提案变化和事故状态变化测试。
- [ ] 基于 HMAC 实现审批令牌。
- [ ] 实现批准、拒绝、过期和失效状态。
- [ ] 扩展 HTML 邮件模板，加入风险、证据、动作、验证、回滚和审批按钮。
- [ ] 批准后重新运行 Policy Engine，禁止邮件点击绕过硬规则。
- [ ] 运行审批测试，预期全部通过。

### Task 5：动态工具领域和持久化

**创建：**

- aiops-server/src/main/java/org/example/incident/tool/ToolManifest.java
- aiops-server/src/main/java/org/example/incident/tool/ToolLifecycleStatus.java
- aiops-server/src/main/java/org/example/incident/tool/DynamicToolService.java
- aiops-server/src/main/java/org/example/incident/persistence/DynamicToolRecord.java
- aiops-server/src/main/java/org/example/incident/persistence/DynamicToolVersionRecord.java
- aiops-server/src/main/java/org/example/incident/persistence/ToolSandboxRunRecord.java
- aiops-server/src/test/java/org/example/incident/tool/DynamicToolServiceTest.java

**修改：**

- aiops-server/src/main/resources/schema.sql

**步骤：**

- [ ] 编写工具版本、哈希变化、非法状态迁移和撤销测试。
- [ ] 建立工具、版本、沙箱执行和执行统计表。
- [ ] 实现源码规范化和 SHA-256 制品哈希。
- [ ] 实现生命周期迁移和撤销。
- [ ] 关联工具版本、审核和生产执行记录。
- [ ] 运行 DynamicToolServiceTest，预期全部通过。

### Task 6：Tool Builder Agent 和沙箱服务

**创建：**

- tool-sandbox-runner/pom.xml
- tool-sandbox-runner/src/main/java/org/example/sandbox/ToolSandboxApplication.java
- tool-sandbox-runner/src/main/java/org/example/sandbox/api/SandboxRunController.java
- tool-sandbox-runner/src/main/java/org/example/sandbox/core/SandboxExecutionService.java
- tool-sandbox-runner/src/main/java/org/example/sandbox/core/ScriptPolicyValidator.java
- tool-sandbox-runner/src/main/resources/application.yml
- tool-sandbox-runner/Dockerfile
- aiops-server/src/main/java/org/example/incident/tool/ToolBuilderAgent.java
- aiops-server/src/main/java/org/example/incident/tool/SandboxGateway.java

**修改：**

- pom.xml
- docker-compose.autonomous.yml

**测试：**

- tool-sandbox-runner/src/test/java/org/example/sandbox/core/ScriptPolicyValidatorTest.java
- tool-sandbox-runner/src/test/java/org/example/sandbox/core/SandboxExecutionServiceTest.java

**步骤：**

- [ ] 编写危险命令、超时、输出超限、后台进程和环境变量访问测试。
- [ ] 实现脚本策略校验和受限 ProcessBuilder 执行。
- [ ] 在 Docker Compose 中加入无宿主端口、只读文件系统、无生产凭证的沙箱服务。
- [ ] 实现控制面到沙箱的 HMAC 调用。
- [ ] 实现 Tool Builder 结构化输出和制品保存。
- [ ] 沙箱通过后只进入 REVIEWED，不直接进入生产执行器。
- [ ] 运行 mvn -pl tool-sandbox-runner test，预期全部通过。

### Task 7：金丝雀执行、工具晋升和撤销

**修改：**

- ops-contract/src/main/java/org/example/ops/contract/ActionRequest.java
- ops-contract/src/main/java/org/example/ops/contract/ActionResult.java
- ops-executor/src/main/java/org/example/executor/core/DockerActionExecutor.java
- ops-executor/src/main/java/org/example/executor/core/ExecutorActionJournal.java
- aiops-server/src/main/java/org/example/incident/executor/ExecutorGateway.java
- aiops-server/src/main/java/org/example/incident/application/RemediationVerifier.java

**创建：**

- aiops-server/src/main/java/org/example/incident/tool/ToolPromotionService.java
- aiops-server/src/test/java/org/example/incident/tool/ToolPromotionServiceTest.java
- ops-executor/src/test/java/org/example/executor/core/DockerActionExecutorTest.java

**步骤：**

- [ ] 编写目标范围、提案哈希、工具版本和重复执行测试。
- [ ] 扩展执行合同，加入 proposalId、proposalHash、toolId、toolVersion 和 canaryScope。
- [ ] Executor 拒绝未批准工具、哈希不匹配和范围扩大请求。
- [ ] Verifier 使用业务健康指标，不只检查命令退出码。
- [ ] 实现成功计数、失败撤销和回滚后撤销。
- [ ] 连续成功 3 次后进入 TRUSTED，AUTO_APPROVED 仍由管理员显式开启。
- [ ] 运行合同、执行器和晋升测试，预期全部通过。

### Task 8：Vue 前端

在实施本任务前按当时的 Vue 目录结构定位具体文件，只修改前端项目，不将页面重新放入后端资源目录。

**交付：**

- [ ] 事故详情页增加调查时间线和提案审核区域。
- [ ] 展示风险维度、最终分数和决策理由。
- [ ] 新增工具中心，展示版本、状态、沙箱报告、成功率和撤销入口。
- [ ] 新增审批详情页，处理批准、拒绝、过期和失效状态。
- [ ] 审批页面不允许修改提案参数。
- [ ] 完成桌面和移动端布局测试。

### Task 9：端到端演练和接入文档

**修改：**

- docker-compose.e2e.yml
- demo-service 故障注入代码

**创建：**

- docs/autonomous-agent-integration.md
- docs/risk-policy-reference.md
- docs/dynamic-tool-lifecycle.md

**演练场景：**

1. 已知低风险故障自动恢复。
2. 未知故障经多轮调查后使用现有动作恢复。
3. 风险 3.01 至 6 分时执行单实例金丝雀。
4. 风险 6.01 至 8 分时发送邮件，批准后执行。
5. 审批令牌过期、重复使用和篡改时拒绝。
6. Tool Builder 生成只读工具，沙箱测试后用于补证。
7. 新写工具首次执行要求人工批准。
8. 动态工具失败后回滚并进入 REVOKED。
9. RabbitMQ 重复投递时只执行一次。
10. LLM、Redis、RabbitMQ、邮件或沙箱不可用时安全降级。

---

## 14. 验收标准

### 功能验收

- 未知故障能触发至少两轮动态调查。
- Agent 不能越过 Policy Engine 调用 Executor。
- Review Agent 要求补证后，系统能回到调查状态并保留原提案。
- 相同风险输入始终得到相同决策。
- 0 至 3 分自动执行，3.01 至 6 分金丝雀执行，6.01 至 8 分邮件审批，8.01 分以上拒绝。
- 审批令牌篡改、过期、重复使用和提案变化均被拒绝。
- 新工具进入生产前具备源码、清单、测试、沙箱报告、审核记录和制品哈希。
- 动态写工具首次执行存在人工审批记录。
- 执行失败触发回滚、工具撤销和邮件通知。
- 成功工具和故障特征能够沉淀到工具目录和 Runbook。

### 安全验收

- 沙箱没有生产凭证、宿主目录和 Docker Socket。
- 沙箱服务没有宿主机映射端口。
- Executor 拒绝未签名、过期、哈希不匹配和重复请求。
- LLM 输出不能覆盖硬编码风险元数据和禁止规则。
- 日志不输出邮箱授权码、模型 API Key、HMAC Secret 和原始审批令牌。

### 稳定性验收

- RabbitMQ 重复消息不会导致重复生产动作。
- Redis 租约竞争时只有一个执行者获得权限。
- LLM 不可用时，固定动作和人工通知链路仍可运行。
- Review Agent 输出不合法时升级人工，不默认批准。
- 沙箱超时、崩溃或输出超限时，工具不得晋升。

---

## 15. 发布、功能开关和回滚

新增功能开关：

~~~text
AIOPS_INVESTIGATION_AGENT_ENABLED
AIOPS_REVIEW_AGENT_ENABLED
AIOPS_RISK_BASED_AUTONOMY_ENABLED
AIOPS_EMAIL_APPROVAL_ENABLED
AIOPS_DYNAMIC_TOOL_BUILDER_ENABLED
AIOPS_DYNAMIC_TOOL_PRODUCTION_ENABLED
~~~

默认全部关闭。上线顺序为调查 Agent、审核 Agent、风险分级、邮件审批、只读动态工具、生产动态工具。

关闭任一开关后，新事故回到现有固定动作或人工通知链路。回滚不删除已经生成的提案、审核、审批和工具记录。已经处于执行状态的动作按当前 Executor 和 Verifier 逻辑完成或回滚，不能通过关闭开关中途丢弃。

---

## 16. 外部依赖和凭证

本计划不要求购买新的外部服务，继续使用：

- DashScope API Key，用于调查、提案、审核和工具生成。
- SMTP 地址、发件邮箱和授权码，用于审批与事故通知。
- MySQL，用于事故、提案、审核、审批和工具持久化。
- Redis，用于租约、冷却、幂等、熔断和一次性审批状态。
- RabbitMQ，用于事故、审核、审批、工具测试和执行事件。
- HMAC Secret，用于控制面、执行器、沙箱和审批令牌签名。

审核 Agent 初期可以与运维 Agent 使用同一模型，但必须使用独立系统提示词和上下文。以后可以给审核 Agent 配置不同模型，降低共同错误概率。

---

## 17. 最脆弱的假设

本计划假设自动动作具有客观成功指标和明确回滚方式。如果某个动作无法验证或无法回滚，即使计算风险不超过 6 分，也必须升级人工审批。

计划还假设接入项目能提供标准化只读接口，包括日志、指标、健康检查、版本和最近发布记录。缺少这些证据时，Agent 只能形成低置信度猜测，不能自动执行写操作。

---

## 18. 完成后的项目定位

完成本计划后，项目可以描述为：

> 基于多 Agent 协作与风险分级自治的 AIOps 系统。调查 Agent 面向未知故障动态收集证据并维护故障假设，处置 Agent 生成结构化修复提案或新工具，审核 Agent 独立评估证据和动作风险，确定性策略引擎依据影响范围、数据风险、可回滚性、权限和工具成熟度决定自动执行、金丝雀处置、邮件审批或拒绝。新工具在隔离 Docker 沙箱中测试，通过审核和首次人工批准后进入工具生命周期，并根据生产验证结果晋升或撤销。

