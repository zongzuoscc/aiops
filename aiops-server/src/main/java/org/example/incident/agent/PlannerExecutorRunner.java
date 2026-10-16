package org.example.incident.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import org.example.incident.persistence.IncidentRecord;
import org.example.incident.persistence.ManagedServiceRecord;
import org.example.incident.safety.RedisSafetyStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class PlannerExecutorRunner {
    private final IncidentAgentTools tools;
    private final PlannerExecutorAgents agents;
    private final AgentRunLimits limits;
    private final ExecutorService modelExecutor;

    public PlannerExecutorRunner(IncidentAgentTools tools, PlannerExecutorAgents agents,
            AgentRunLimits limits, @Qualifier("opsAgentModelExecutor") ExecutorService modelExecutor) {
        this.tools = tools;
        this.agents = agents;
        this.limits = limits;
        this.modelExecutor = modelExecutor;
    }

    public void run(IncidentRecord incident, ManagedServiceRecord service, RedisSafetyStateStore.Lease lease) {
        IncidentAgentTools.Session session = tools.open(incident, service, lease);
        try {
            StateGraph graph = new StateGraph();
            graph.addNode("planner", node_async(state -> {
                String route = session.routeBeforePlanning();
                if ("PLAN".equals(route)) {
                    AgentPlan plan = modelCall(() -> agents.plan(session.context()), session);
                    route = session.accept(plan);
                }
                return Map.of("route", route);
            }));
            graph.addNode("executor", node_async(state -> {
                modelCall(() -> {
                    agents.execute(session.checkpoint().getDecision(), session.selectedTool());
                    return Boolean.TRUE;
                }, session);
                session.missingToolCall();
                return Map.of("route", "REPLAN");
            }));
            graph.addEdge(StateGraph.START, "planner");
            graph.addConditionalEdges("planner", edge_async(state -> (String) state.value("route").orElseThrow()),
                    Map.of("EXECUTE", "executor", "REPLAN", "planner", "END", StateGraph.END));
            graph.addEdge("executor", "planner");
            graph.compile(CompileConfig.builder().recursionLimit(limits.segmentRounds() * 4 + 8).build())
                    .invoke(Map.of("route", "REPLAN"));
            if (!session.stopped()) session.manual("Graph ended without an accepted completion condition");
        } catch (Exception ex) {
            session.cancel();
            session.manual("Agent could not continue: " + ex.getClass().getSimpleName());
        }
    }

    private <T> T modelCall(Callable<T> action, IncidentAgentTools.Session session) throws Exception {
        long timeout = Math.min(TimeUnit.SECONDS.toMillis(limits.modelTimeoutSeconds()), session.remainingMillis());
        if (timeout <= 0) throw new TimeoutException("Agent task deadline reached");
        var future = modelExecutor.submit(action);
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            session.cancel();
            future.cancel(true);
            throw ex;
        }
    }
}
