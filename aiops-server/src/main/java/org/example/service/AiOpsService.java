package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.IncidentOperationsTools;
import org.example.agent.tool.OperationalPlaybookTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    @Autowired
    private OperationalPlaybookTools operationalPlaybookTools;

    @Autowired
    private IncidentOperationsTools incidentOperationsTools;

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String taskPrompt = """
                你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行“规划→执行→再规划”闭环。
                你必须把所有决策和结论基于工具可观测证据，不得编造数据。若同一方向连续 3 次工具失败，必须在最终报告中明确说明“任务无法完成”及失败原因，并给出人工兜底建议。
                先执行一次 triage 估计：先调用 buildIncidentTriagePackage，给出优先级、处理窗口与责任人建议，再进入告警执行流程。
                若 triage 分数为 P0 或受影响服务数>=3，立即补充 buildDependencyRiskMatrix 与 buildRecoveryExecutionPlan，先给出封控与验证顺序，再执行。
                最终输出应包含告警列表、影响范围、已执行动作、验证结果、风险评估、回滚建议、交接建议与下一步建议（含责任人、验证周期）。
                全流程不得超过 10 轮，如超时立即 FINISH 并给出收敛说明。
                """;

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的告警分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools, operationalPlaybookTools, incidentOperationsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, operationalPlaybookTools, incidentOperationsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 任何时候发现新告警/症状后，优先调用 getIncidentPlaybook(symptom) 获取结构化处理流程；若 playbook 未能返回有效建议，必须在 output 中标注 "PLAYBOOK_MISSING"，并转向人工核验。
                4. 在执行涉及发布、版本切换、配置变更或回退动作前，必须先调用 validateDeploymentWindow(serviceName, expectedDurationMinutes, criticality, rollbackReady) 做风险评估；若风险等级为 HIGH，必须要求分批/灰度策略并降低执行优先级。
                5. 当出现明确回滚触发条件或验证失败时，必须先调用 generateRollbackPlan(serviceName, deploymentVersion, triggerReason, severity) 产出回滚动作清单后再进入下一步。
                6. 在执行阶段，输出纯 JSON，包含:
                   {
                     "decision": "PLAN|EXECUTE|PAUSE|FINISH",
                     "step": "当前步骤摘要",
                     "nextTool": "建议调用的工具名",
                     "arguments": {"参数名":"参数值"},
                     "riskLevel": "LOW|MEDIUM|HIGH",
                     "owner": "责任人",
                     "evidenceNeeded": "需要的验证项列表"
                   }
                   其中 PAUSE 仅在需要人工确认时使用，包含 reason 与 manualCheck。
                7. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                8. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。
                9. 触发高风险路径时先调用 buildIncidentTriagePackage 或 buildDependencyRiskMatrix，再决定动作顺序，避免并行执行导致恢复链路混乱。
                10. 高风险路径下必须在 decision=PLAN 时先输出 buildRecoveryExecutionPlan，然后再进入实际执行。
                
                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 告警分析报告
                
                ---
                
                ## 📋 活跃告警清单
                
                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 告警根因分析1 - [告警名称]
                
                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]
                
                ### 症状描述
                [根据监控指标描述症状]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处理方案执行1 - [告警名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 处理建议
                [给出具体的处理建议]
                
                ### 预期效果
                [说明预期的效果]
                
                ---
                
                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结所有告警的整体情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 后续建议
                1. [建议1]
                2. [建议2]
                
                ### 风险评估
                [评估当前风险等级和影响范围]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 如果 planner_plan 涉及 playbook 执行、发布回退或高风险运维动作，优先执行 getIncidentPlaybook / validateDeploymentWindow / generateRollbackPlan 对应步骤，并把结果写入 evidence。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，格式示例：
                  {
                    "status": "SUCCESS|FAILED|NEED_PAUSE",
                    "summary": "执行摘要",
                    "evidence": "工具返回证据摘要",
                    "nextHint": "建议后续 Planner 执行的下一步",
                    "handoff": "generateOperatorHandoff 返回值",
                    "retryCount": 0
                  }
                  严禁编造未实际查询到的内容。
                - 若本轮决定较高风险动作（影响服务数>=3 或状态异常持续），调用 buildDependencyRiskMatrix 提供依赖链风险再决策。
                - 若存在发布回滚可能或回滚窗口受限，必须调用 buildRecoveryExecutionPlan(service/incident...) 给出恢复和回退边界。
                - 每次执行结束前，必须补齐 generateOperatorHandoff(incidentId, owner, conclusion, nextActions)，便于后续值班交接。


                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 每一轮必须更新 iterationCount，超过 8 轮自动进入 FINISH 并给出收敛说明。
                7. 规则：同一方向连续 3 次工具失败或连续 2 轮反馈 status=FAILED，必须终止该方向并进入 FINISH。
                8. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。
                当 FINISH 触发时，必须保持最终输出是《告警分析报告》，并在报告结论段明确写明是否存在未完成步骤。

                """;
    }
}
