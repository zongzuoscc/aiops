package org.example.incident.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.incident.application.RemediationVerifier;
import org.example.incident.application.WorkflowPersistence;
import org.example.incident.domain.IncidentStateMachine;
import org.example.incident.domain.IncidentStatus;
import org.example.incident.executor.ExecutorGateway;
import org.example.incident.persistence.*;
import org.example.incident.policy.AutomationPolicyEngine;
import org.example.incident.safety.RedisSafetyStateStore;
import org.example.ops.contract.ActionResult;
import org.example.ops.contract.ActionType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses real ReactAgents and StateGraph; only external model/network/storage boundaries are replaced. */
class PlannerExecutorRunnerTest {
    @Test
    void followsTheModelsChosenLogFirstOrderAndClosesOnlyAfterActualVerification() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.model.step("getContainerLogs").step("getContainerState").step("verifyRecovery").finish();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(f.checkpoint().getObservations()).extracting(AgentObservation::tool)
                    .containsExactly("getContainerLogs", "getContainerState", "verifyRecovery");
            assertThat(f.checkpoint().isVerified()).isTrue();
            assertThat(f.checkpoint().getFinalReport()).contains("RESOLVED", "getContainerLogs");
            assertThat(f.executed).doesNotContain(ActionType.RESTART_CONTAINER);
            verify(f.timeline, never()).save(argThat(item -> item.getStage().equals("SNAPSHOT_CLEANUP")));
        }
    }

    @Test
    void rejectingThreePrematureFinishesStopsWithoutClaimingRecovery() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.model.finish().finish().finish();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.checkpoint().isVerified()).isFalse();
            assertThat(f.checkpoint().getObservations()).hasSize(3)
                    .allMatch(item -> item.tool().equals("completionCheck") && !item.success());
            assertThat(f.executed).isEmpty();
            verify(f.timeline, never()).save(argThat(item -> item.getStage().equals("ROLLBACK")));
        }
    }

    @Test
    void anExecutorsTextualSuccessWithoutCallingAToolIsNotAccepted() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.model.plan("getContainerState").text("Already fixed without calling a tool").manual();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.checkpoint().getObservations()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.success()).isFalse();
                        assertThat(item.summary()).contains("without a real tool invocation");
                    });
            assertThat(f.executed).isEmpty();
        }
    }

    @Test
    void anUnexposedWriteToolCannotBeInvokedByTheExecutor() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.model.plan("getContainerState").tool("restartContainer");
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.executed).isEmpty();
            assertThat(f.actionRecords).isEmpty();
        }
    }

    @Test
    void readOnlyInvestigationCannotBeEscalatedToAWritingTaskByTheModel() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.model.step("getContainerState").step("searchRunbooks").step("restartContainer").manual();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.checkpoint().getObservations()).anySatisfy(item -> {
                assertThat(item.tool()).isEqualTo("restartContainer");
                assertThat(item.success()).isFalse();
                assertThat(item.summary()).contains("investigation only");
            });
            assertThat(f.executed).doesNotContain(ActionType.RESTART_CONTAINER);
        }
    }

    @Test
    void anEvidenceBackedApprovedRestartExecutesAndThenRequiresVerification() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.healthy = false;
            f.model.step("getContainerState").step("searchRunbooks").step("restartContainer")
                    .step("verifyRecovery").finish();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(f.executed).contains(ActionType.RESTART_CONTAINER);
            assertThat(f.actionRecords).singleElement().satisfies(action ->
                    assertThat(action.getState()).isEqualTo(ActionExecutionRecord.State.SUCCEEDED));
            assertThat(f.checkpoint().getObservations()).extracting(AgentObservation::tool)
                    .containsExactly("getContainerState", "searchRunbooks", "restartContainer", "verifyRecovery");
        }
    }

    @Test
    void aPlannerCannotWriteWithoutFirstRetrievingAnApprovedRunbook() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.model.step("getContainerState").step("restartContainer").manual();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.actionRecords).isEmpty();
            assertThat(f.executed).doesNotContain(ActionType.RESTART_CONTAINER);
            assertThat(f.checkpoint().getObservations()).anyMatch(item ->
                    !item.success() && item.summary().contains("approved Runbook"));
        }
    }

    @Test
    void aSegmentQueuesContinuationAndRecoversItsEvidenceInsteadOfStartingOver() throws Exception {
        try (Fixture f = new Fixture(false, new AgentRunLimits(1, 8, 12, 60, 10))) {
            f.model.step("getContainerLogs").step("verifyRecovery").finish();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.RETRY_SCHEDULED);
            assertThat(f.checkpoint().getRound()).isEqualTo(1);
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.RETRY_SCHEDULED);
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(f.checkpoint().getObservations()).extracting(AgentObservation::tool)
                    .containsExactly("getContainerLogs", "verifyRecovery");
            assertThat(f.executed.stream().filter(action -> action == ActionType.GET_CONTAINER_LOGS)).hasSize(1);
        }
    }

    @Test
    void aFailedRecreationCanBeRolledBackAndVerifiedButStillRequiresHumanReview() throws Exception {
        try (Fixture f = new Fixture(false)) {
            ReflectionTestUtils.setField(f.book, "recommendedAction", "RECREATE_WITH_STABLE_IMAGE");
            f.healthy = false;
            f.model.step("getContainerState").step("searchRunbooks").step("recreateWithStableImage")
                    .step("verifyRecovery").step("rollback").step("verifyRecovery").manual();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.checkpoint().isRolledBack()).isTrue();
            assertThat(f.checkpoint().isVerified()).isTrue();
            assertThat(f.executed).contains(ActionType.RECREATE_WITH_STABLE_IMAGE, ActionType.RESTORE_SNAPSHOT);
            assertThat(f.actionRecords).singleElement().satisfies(action ->
                    assertThat(action.getState()).isEqualTo(ActionExecutionRecord.State.ROLLED_BACK));
            assertThat(f.checkpoint().getObservations()).anyMatch(item ->
                    item.tool().equals("verifyRecovery") && !item.success());
        }
    }

    @Test
    void manualEscalationAfterAFailedRecreationAttemptsTheRecordedSafetyCompensation() throws Exception {
        try (Fixture f = new Fixture(false)) {
            ReflectionTestUtils.setField(f.book, "recommendedAction", "RECREATE_WITH_STABLE_IMAGE");
            f.healthy = false;
            f.model.step("getContainerState").step("searchRunbooks").step("recreateWithStableImage")
                    .step("verifyRecovery").manual();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.executed.stream().filter(action -> action == ActionType.RESTORE_SNAPSHOT)).hasSize(1);
            assertThat(f.checkpoint().getObservations()).anyMatch(item ->
                    item.tool().equals("rollbackVerification") && item.success());
        }
    }

    @Test
    void anUnknownWriteOutcomeStopsRatherThanPlanningAnotherWrite() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.unknownWrite = true;
            f.model.step("getContainerState").step("searchRunbooks").step("restartContainer");
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.incident.getPolicyReason()).contains("outcome is unknown");
            assertThat(f.executed.stream().filter(action -> action == ActionType.RESTART_CONTAINER)).hasSize(1);
            assertThat(f.checkpoint().getRound()).isEqualTo(3);
        }
    }

    @Test
    void anExpiredTaskCannotStartAModelOrToolCall() throws Exception {
        try (Fixture f = new Fixture(false, new AgentRunLimits(1, 4, 4, 1, 1))) {
            f.incident.recordDiagnosis(f.mapper.writeValueAsString(AgentCheckpoint.fresh(Instant.now().minusSeconds(2))));
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.incident.getPolicyReason()).contains("budget exhausted");
            assertThat(f.executed).isEmpty();
            assertThat(f.checkpoint().getRound()).isZero();
        }
    }

    @Test
    void aRunbookNameCannotImpersonateTheIdentifierOfAnUnretrievedRunbook() throws Exception {
        try (Fixture f = new Fixture(false)) {
            AgentCheckpoint checkpoint = AgentCheckpoint.fresh(Instant.now());
            checkpoint.getObservations().add(new AgentObservation(0, "getContainerState", true,
                    "Container evidence", Map.of("running", true)));
            checkpoint.getObservations().add(new AgentObservation(0, "searchRunbooks", true,
                    "Approved Runbooks retrieved", Map.of("runbooks", List.of(
                            Map.of("id", "different-runbook", "name", "approved")))));
            f.incident.recordDiagnosis(f.mapper.writeValueAsString(checkpoint));
            f.model.step("restartContainer").manual();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.MANUAL_REQUIRED);
            assertThat(f.executed).doesNotContain(ActionType.RESTART_CONTAINER);
            assertThat(f.actionRecords).isEmpty();
        }
    }

    @Test
    void aCompletedWriteIsRecoveredFromItsJournalDespiteTheNewCooldown() throws Exception {
        try (Fixture f = new Fixture(false)) {
            AgentCheckpoint checkpoint = AgentCheckpoint.fresh(Instant.now());
            checkpoint.getObservations().add(new AgentObservation(0, "getContainerState", true,
                    "Actual container state", Map.of("running", true)));
            checkpoint.getObservations().add(new AgentObservation(0, "searchRunbooks", true,
                    "Approved Runbooks", Map.of("runbooks", List.of(Map.of("id", "approved")))));
            checkpoint.plan(new AgentPlan(AgentPlan.Decision.EXECUTE, "restartContainer", "health", "approved",
                    0.95, "Resume the persisted restart step"));
            f.incident.recordDiagnosis(f.mapper.writeValueAsString(checkpoint));
            String key = f.incident.getId() + ":agent:1:restartContainer";
            ActionExecutionRecord action = ActionExecutionRecord.pending(f.incident.getId(), key,
                    ActionType.RESTART_CONTAINER, "order", "{}");
            action.running();
            ActionResult result = new ActionResult(key, ActionType.RESTART_CONTAINER, "order", true,
                    "Previously completed restart", Map.of(), Map.of(), Instant.now());
            action.complete(result, f.mapper.writeValueAsString(result), "{}");
            f.actionRecords.add(action);
            ReflectionTestUtils.setField(f.incident, "status", IncidentStatus.EXECUTING);
            when(f.safety.snapshot(anyString(), any())).thenReturn(new RedisSafetyStateStore.SafetySnapshot(
                    true, 2, Duration.ZERO, false));
            f.model.tool("restartContainer").step("verifyRecovery").finish();
            f.run();
            assertThat(f.incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(f.checkpoint().getObservations()).anyMatch(item -> item.tool().equals("restartContainer")
                    && item.success() && item.summary().equals("Previously completed restart"));
            assertThat(f.executed).doesNotContain(ActionType.RESTART_CONTAINER);
            verify(f.safety, never()).recordWrite(anyString(), any());
        }
    }

    private static final class ScriptedModel implements ChatModel {
        private final Queue<AssistantMessage> responses = new ArrayDeque<>();

        ScriptedModel plan(String tool) {
            return text("{\"decision\":\"EXECUTE\",\"tool\":\"" + tool
                    + "\",\"query\":\"unhealthy order service\",\"runbookId\":\"approved\","
                    + "\"confidence\":0.95,\"summary\":\"Investigate using " + tool + "\"}");
        }
        // The native one-tool limit hook ends the Executor after the real tool result, without an extra model turn.
        ScriptedModel step(String tool) { return plan(tool).tool(tool); }
        ScriptedModel finish() {
            return text("{\"decision\":\"FINISH\",\"confidence\":1,\"summary\":\"Investigation complete\"}");
        }
        ScriptedModel manual() {
            return text("{\"decision\":\"MANUAL\",\"confidence\":0,\"summary\":\"Operator must investigate\"}");
        }
        ScriptedModel text(String text) { responses.add(new AssistantMessage(text)); return this; }
        ScriptedModel tool(String name) {
            responses.add(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, "{}"))).build());
            return this;
        }
        @Override public ChatResponse call(Prompt prompt) {
            AssistantMessage response = responses.poll();
            if (response == null) throw new IllegalStateException("Unexpected extra model call");
            return new ChatResponse(List.of(new Generation(response)));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final ScriptedModel model = new ScriptedModel();
        final ExecutorService modelThreads = Executors.newFixedThreadPool(2);
        final List<ActionType> executed = new ArrayList<>();
        final List<ActionExecutionRecord> actionRecords = new ArrayList<>();
        final IncidentTimelineRepository timeline = mock(IncidentTimelineRepository.class);
        final RedisSafetyStateStore safety = mock(RedisSafetyStateStore.class);
        final IncidentRecord incident;
        final ManagedServiceRecord service;
        final PlannerExecutorRunner runner;
        final RunbookRecord book;
        boolean healthy = true;
        boolean unknownWrite;

        Fixture(boolean readOnly) { this(readOnly, new AgentRunLimits(8, 24, 32, 60, 10)); }

        Fixture(boolean readOnly, AgentRunLimits limits) {
            incident = IncidentRecord.receive("test", "event-1", "fingerprint", "order", "CRITICAL",
                    "unhealthy order service", "{\"labels\":{\"execution_mode\":\""
                            + (readOnly ? "OBSERVE_ONLY" : "REMEDIATE") + "\"}}");
            service = ManagedServiceRecord.create("order", "Order", "order-api", "http://order/health", "order:stable",
                    EnumSet.allOf(ActionType.class), true, false, 0.8, 2, 30);
            var incidents = mock(IncidentRecordRepository.class);
            when(incidents.findById(incident.getId())).thenAnswer(invocation -> Optional.of(incident));
            when(timeline.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            var actions = mock(ActionExecutionRepository.class);
            when(actions.findByIdempotencyKey(anyString())).thenAnswer(invocation -> actionRecords.stream()
                    .filter(item -> item.getIdempotencyKey().equals(invocation.getArgument(0))).findFirst());
            when(actions.findById(anyString())).thenAnswer(invocation -> actionRecords.stream()
                    .filter(item -> item.getId().equals(invocation.getArgument(0))).findFirst());
            when(actions.findFirstByIncidentIdOrderByCreatedAtDesc(anyString())).thenAnswer(invocation ->
                    actionRecords.isEmpty() ? Optional.empty() : Optional.of(actionRecords.get(actionRecords.size() - 1)));
            when(actions.save(any())).thenAnswer(invocation -> {
                ActionExecutionRecord action = invocation.getArgument(0);
                actionRecords.add(action);
                return action;
            });
            var outbox = mock(OutboxEventRepository.class);
            var persistence = new WorkflowPersistence(incidents, timeline, actions, outbox, new IncidentStateMachine(), mapper);
            var gateway = mock(ExecutorGateway.class);
            when(gateway.invoke(anyString(), anyString(), any(), anyString(), anyMap())).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                ActionType action = invocation.getArgument(2);
                executed.add(action);
                if (action == ActionType.RESTART_CONTAINER && unknownWrite) {
                    return new ActionResult(id, action, "order", false, "Action outcome is unknown",
                            Map.of("outcome", "UNKNOWN"), Map.of(), Instant.now());
                }
                if (action == ActionType.RESTART_CONTAINER) healthy = true;
                if (action == ActionType.RECREATE_WITH_STABLE_IMAGE) healthy = false;
                if (action == ActionType.RESTORE_SNAPSHOT) healthy = true;
                Map<String, Object> data = switch (action) {
                    case GET_CONTAINER_STATE -> Map.of("running", true, "status", "running");
                    case CHECK_HTTP_HEALTH -> Map.of("statusCode", healthy ? 200 : 500);
                    case GET_CONTAINER_LOGS -> Map.of("logs", "unhealthy order service: connection timeout");
                    default -> Map.of();
                };
                return new ActionResult(id, action, "order", true, "Observed " + action, data,
                        action == ActionType.RECREATE_WITH_STABLE_IMAGE ? Map.of("snapshot", "order-backup") : Map.of(),
                        Instant.now());
            });
            when(safety.renew(any(), any())).thenReturn(true);
            when(safety.recordWrite(anyString(), any())).thenReturn(true);
            when(safety.snapshot(anyString(), any())).thenReturn(new RedisSafetyStateStore.SafetySnapshot(
                    true, 0, Duration.ofHours(1), false));
            book = org.springframework.beans.BeanUtils.instantiateClass(RunbookRecord.class);
            ReflectionTestUtils.setField(book, "id", "approved");
            ReflectionTestUtils.setField(book, "name", "Approved restart");
            ReflectionTestUtils.setField(book, "symptomKeywords", "unhealthy");
            ReflectionTestUtils.setField(book, "diagnosisHint", "Restart the approved service");
            ReflectionTestUtils.setField(book, "recommendedAction", "RESTART_CONTAINER");
            ReflectionTestUtils.setField(book, "minConfidence", 0.8);
            var runbooks = mock(RunbookRepository.class);
            when(runbooks.findByEnabledTrue()).thenReturn(List.of(book));
            var alerts = mock(AlertEventRepository.class);
            when(alerts.findByIncidentIdOrderByReceivedAt(anyString())).thenReturn(List.of());
            var tools = new IncidentAgentTools(persistence, gateway, new RemediationVerifier(gateway, 1, 0),
                    new AutomationPolicyEngine(), safety, runbooks, alerts, mock(InternalDocsTools.class),
                    mock(QueryMetricsTools.class), mapper, limits);
            var beans = new StaticListableBeanFactory();
            beans.addBean("model", model);
            runner = new PlannerExecutorRunner(tools, new PlannerExecutorAgents(beans.getBeanProvider(ChatModel.class)),
                    limits, modelThreads);
        }

        void run() { runner.run(incident, service, new RedisSafetyStateStore.Lease("test", "token")); }
        AgentCheckpoint checkpoint() throws Exception { return mapper.readValue(incident.getDiagnosis(), AgentCheckpoint.class); }
        @Override public void close() { modelThreads.shutdownNow(); }
    }
}
