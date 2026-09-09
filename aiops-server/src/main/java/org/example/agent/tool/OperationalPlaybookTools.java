package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OperationalPlaybookTools {

    private static final Logger logger = LoggerFactory.getLogger(OperationalPlaybookTools.class);

    public static final String TOOL_GET_INCIDENT_PLAYBOOK = "getIncidentPlaybook";
    public static final String TOOL_GENERATE_ROLLBACK_PLAN = "generateRollbackPlan";
    public static final String TOOL_VALIDATE_DEPLOYMENT_WINDOWS = "validateDeploymentWindow";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Get a practical SRE incident playbook for a given symptom or alert name. " +
            "The playbook includes containment, investigation, mitigation, verification and communication steps.")
    public String getIncidentPlaybook(@ToolParam(description = "Alert name, symptom keyword, or incident tag, e.g. HighCPUUsage, ServiceUnavailable") String symptom) {
        logger.info("获取 incident playbook: {}", symptom);
        String normalized = normalize(symptom);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("scenario", normalized);

        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("containment", List.of(
                "确认告警影响范围（服务、可用区、依赖链）",
                "记录告警时间线并开启事故事件",
                "若可能，执行流控/限流避免雪崩扩散"
        ));
        checklist.put("investigation", List.of(
                "按时间倒序核对告警与日志证据",
                "确认近期变更、部署、参数变更历史",
                "排查上游下游依赖链和资源瓶颈"
        ));
        checklist.put("mitigation", List.of(
                "执行快速缓解动作（限流、熔断、隔离单实例）",
                "核对数据库与队列积压是否继续增长",
                "确认重试/超时策略是否有异常放大效应"
        ));
        checklist.put("verification", List.of(
                "验证关键 SLI（可用率、错误率、延迟）恢复趋势",
                "对比回归窗口前后指标，确认未出现次生事故",
                "要求工具输出至少 3 条以上正向证据后再关闭告警"
        ));
        checklist.put("communication", List.of(
                "输出当前决策链路与证据来源",
                "形成可追溯处理动作清单",
                "在事故结束后补录 postmortem"
        ));

        result.put("checklist", checklist);
        return writeValueQuietly(result);
    }

    @Tool(description = "Generate a rollback plan template for service change incidents. " +
            "Use this before rolling back a deployment and include safety gates.")
    public String generateRollbackPlan(@ToolParam(description = "Target service name") String serviceName,
                                      @ToolParam(description = "Deployment tag/version, e.g., release-20260814") String deploymentVersion,
                                      @ToolParam(description = "Rollback trigger reason") String triggerReason,
                                      @ToolParam(description = "Current incident severity, e.g., P0/P1/P2/P3") String severity) {
        logger.info("生成回滚计划: service={}, version={}", serviceName, deploymentVersion);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("service", normalize(serviceName));
        output.put("version", normalize(deploymentVersion));
        output.put("triggerReason", normalize(triggerReason));
        output.put("severity", normalize(severity));

        output.put("precondition", List.of(
                "确认变更影响范围（实例数、可用区、依赖链）",
                "确认是否支持快速回滚（是否有可用镜像/制品）",
                "确认回滚窗口可接受业务影响范围"
        ));
        output.put("rollbackSteps", List.of(
                "停止流量导向/下线可疑实例（按流量比例）",
                "逐步回滚到上一个稳定版本（每 2-5 分钟观察一次）",
                "同步检查 Prometheus/SLO 与错误日志",
                "恢复全部实例前确认无新异常回退指标"
        ));
        output.put("validation", List.of(
                "CPU/Mem/5xx/延迟在 5 分钟窗口内回落",
                "队列与 DB 锁等待不再持续上升",
                "日志中无新增 Fatal/OOM/Downgrade 级别异常"
        ));
        output.put("postActions", List.of(
                "通知 oncall 与业务方",
                "锁定触发回滚版本的变更记录",
                "补录回滚依据与证据"
        ));
        return writeValueQuietly(output);
    }

    @Tool(description = "Validate whether now is a safe deployment window. " +
            "Returns a risk score and quick checklist for maintenance safety.")
    public String validateDeploymentWindow(@ToolParam(description = "Service name") String serviceName,
                                          @ToolParam(description = "Expected maintenance duration in minutes") Integer expectedDurationMinutes,
                                          @ToolParam(description = "Business criticality: high/medium/low") String criticality,
                                          @ToolParam(description = "是否有可回滚能力（true/false）") Boolean rollbackReady) {
        int duration = expectedDurationMinutes == null ? 0 : expectedDurationMinutes;
        String criticalityValue = normalize(criticality);
        boolean canRollback = Boolean.TRUE.equals(rollbackReady);
        int riskScore = computeRiskScore(duration, criticalityValue, canRollback);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", true);
        output.put("service", normalize(serviceName));
        output.put("expectedDurationMinutes", duration);
        output.put("criticality", criticalityValue);
        output.put("rollbackReady", canRollback);
        output.put("riskScore", riskScore);
        output.put("riskLevel", riskScore >= 70 ? "HIGH" : riskScore >= 40 ? "MEDIUM" : "LOW");
        output.put("riskFactors", List.of(
                "是否触发峰值时段",
                "依赖链复杂度",
                "回滚与验证时间窗口",
                "是否有跨区/跨云依赖"
        ));
        output.put("suggestions", suggestionList(riskScore));
        return writeValueQuietly(output);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private int computeRiskScore(int durationMinutes, String criticality, boolean rollbackReady) {
        int score = 20;
        if (durationMinutes >= 60) {
            score += 30;
        } else if (durationMinutes >= 30) {
            score += 15;
        }
        if ("high".equalsIgnoreCase(criticality)) {
            score += 30;
        } else if ("medium".equalsIgnoreCase(criticality)) {
            score += 15;
        }
        if (!rollbackReady) {
            score += 35;
        }
        return Math.min(score, 100);
    }

    private List<String> suggestionList(int riskScore) {
        if (riskScore >= 70) {
            return Arrays.asList(
                    "建议启用灰度发布逐步验证",
                    "加密核对回滚按钮与凭据可用性",
                    "必须安排 24h 在岗观察或双人值班"
            );
        }
        if (riskScore >= 40) {
            return Arrays.asList(
                    "建议分批次发布并延长验证窗口",
                    "优先观察 关键链路的 P99 与 5xx",
                    "提前确认回滚链路与版本可用"
            );
        }
        return Arrays.asList(
                "标准发布流程执行即可",
                "保持 1~2 轮验证后再恢复全部流量",
                "记录发布步骤与异常边界"
        );
    }

    private String writeValueQuietly(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            logger.error("序列化工具结果失败", e);
            return "{\"success\":false,\"message\":\"工具输出序列化失败\"}";
        }
    }
}
