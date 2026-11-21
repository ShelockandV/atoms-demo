package dev.atoms.agent;

import dev.atoms.agent.persistence.Store;
import dev.atoms.agent.pattern.WorkflowTemplate;
import dev.atoms.agent.model.TextModelTransport;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleException;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.RetryOptions;
import io.grpc.Status;
import org.springframework.beans.factory.ObjectProvider;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Persists one Temporal workflow per accepted Atoms run. */
@Component
@ConditionalOnProperty(name = "atoms.temporal.enabled", havingValue = "true")
public class TemporalTaskRuntime {
    private final Store store;
    private final WorkflowServiceStubs service;
    private final WorkflowClient client;
    private final WorkerFactory factory;
    private final String queue;
    private final String evidenceQueue;
    private final boolean evidenceWorkerEnabled;
    private final String agentsQueue;
    private final boolean agentWorkerEnabled;
    private final ScheduleClient scheduleClient;
    private final ObjectProvider<ScheduledTasks> schedules;

    public TemporalTaskRuntime(Store store, ObjectProvider<ScheduledTasks> schedules,
                               @Value("${atoms.temporal.target:127.0.0.1:7233}") String target,
                               @Value("${atoms.temporal.namespace:default}") String namespace,
                               @Value("${atoms.temporal.task-queue:atoms-generation}") String queue,
                               @Value("${atoms.temporal.research-evidence-worker.enabled:true}") boolean evidenceWorkerEnabled,
                               @Value("${atoms.temporal.agent-worker.enabled:true}") boolean agentWorkerEnabled) {
        this.store = store;
        this.queue = queue;
        this.evidenceQueue = queue + "-research-evidence";
        this.evidenceWorkerEnabled = evidenceWorkerEnabled;
        this.agentsQueue = queue + "-agents";
        this.agentWorkerEnabled = agentWorkerEnabled;
        this.schedules = schedules;
        service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
        factory = WorkerFactory.newInstance(client);
        scheduleClient = ScheduleClient.newInstance(service, ScheduleClientOptions.newBuilder().setNamespace(namespace).build());
    }

    public boolean ready() {
        try {
            service.blockingStub().withDeadlineAfter(2, TimeUnit.SECONDS)
                    .describeNamespace(io.temporal.api.workflowservice.v1.DescribeNamespaceRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace()).build());
            return true;
        } catch (io.grpc.StatusRuntimeException unavailable) {
            return false;
        }
    }

    void start(GenerationService generation) {
        var worker = factory.newWorker(queue, WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(2).build());
        worker.registerWorkflowImplementationTypes(TaskWorkflowImpl.class, ScheduledTriggerImpl.class);
        worker.registerActivitiesImplementations(new TaskActivitiesImpl(store, generation), new ScheduledEnqueue(schedules));
        if (evidenceWorkerEnabled) {
            var evidenceWorker = factory.newWorker(evidenceQueue,
                    WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(4).build());
            evidenceWorker.registerWorkflowImplementationTypes(ResearchEvidenceWorkflowImpl.class, ResearchModelWorkflowImpl.class);
            evidenceWorker.registerActivitiesImplementations(new ResearchEvidenceActivityImpl(generation),
                    new ResearchModelActivityImpl(store, generation));
        }
        if (agentWorkerEnabled) {
            var agentWorker = factory.newWorker(agentsQueue,
                    WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(4).build());
            agentWorker.registerWorkflowImplementationTypes(AgentNodeWorkflowImpl.class);
            agentWorker.registerActivitiesImplementations(new AgentNodeActivityImpl(store, generation));
        }
        factory.start();
    }

    void stop() {
        factory.shutdownNow();
        factory.awaitTermination(5, TimeUnit.SECONDS);
        service.shutdownNow();
    }

    void dispatch() {
        for (String id : store.undispatchedTemporalRuns(20)) {
            var workflow = client.newWorkflowStub(TaskWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(id).setTaskQueue(queue)
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .build());
            try {
                WorkflowClient.start(workflow::run, id);
            } catch (WorkflowExecutionAlreadyStarted duplicate) {
                // The first start succeeded before its DB dispatch record was written.
            }
            store.markTemporalDispatched(id);
        }
    }

    void notifyTaskControl(String id) {
        // The committed business state remains authoritative if the workflow has not started or the signal fails.
        try {
            service.blockingStub().withDeadlineAfter(750, TimeUnit.MILLISECONDS).signalWorkflowExecution(
                    io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setWorkflowExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder().setWorkflowId(id))
                            .setSignalName("controlChanged").setRequestId(UUID.randomUUID().toString()).build());
        } catch (io.grpc.StatusRuntimeException unavailable) {
            if (unavailable.getStatus().getCode() != Status.Code.NOT_FOUND)
                org.slf4j.LoggerFactory.getLogger(TemporalTaskRuntime.class)
                        .debug("Could not wake controlled Temporal workflow {}", id, unavailable);
        }
    }

    String researchEvidence(ToolInvocation runtime, String node, String query) throws Exception {
        runtime.checkActive();
        String identity = runtime.token + "\n" + node;
        String id = runtime.rid + "-evidence-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        var parent = childParent(runtime.rid);
        if (parent != null)
            return awaitNode(runtime, parent.startUpdate(UpdateOptions.newBuilder(String.class)
                    .setUpdateName("executeNode").setUpdateId(id)
                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED).build(),
                    new NodeRequest(NodeKind.EVIDENCE, id, runtime.token, query, null, null)).getResultAsync());
        var workflow = client.newWorkflowStub(ResearchEvidenceWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(id).setTaskQueue(evidenceQueue)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(4))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(workflow::run, runtime.rid, runtime.token, query);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            // The same run attempt already submitted this node.
        }
        return awaitNode(runtime, WorkflowStub.fromTyped(workflow));
    }

    String researchModelNode(ToolInvocation runtime, String node, String input) throws Exception {
        runtime.checkActive();
        String identity = runtime.token + "\n" + node;
        String id = runtime.rid + "-model-node-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        var parent = childParent(runtime.rid);
        if (parent != null)
            return awaitNode(runtime, parent.startUpdate(UpdateOptions.newBuilder(String.class)
                    .setUpdateName("executeNode").setUpdateId(id)
                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED).build(),
                    new NodeRequest(NodeKind.RESEARCH_MODEL, id, runtime.token, node, input, null)).getResultAsync());
        var workflow = client.newWorkflowStub(ResearchModelWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(id).setTaskQueue(evidenceQueue)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(65))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(workflow::run, runtime.rid, runtime.token, node, input);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            // The same run attempt already submitted this node.
        }
        return awaitNode(runtime, WorkflowStub.fromTyped(workflow));
    }

    private WorkflowStub childParent(String id) {
        var parent = client.newWorkflowStub(TaskWorkflow.class, id);
        return parent.nativeChildren() ? WorkflowStub.fromTyped(parent) : null;
    }

    private static String awaitNode(ToolInvocation runtime, WorkflowStub workflow) throws Exception {
        return awaitNode(runtime, workflow.getResultAsync(String.class));
    }

    private static String awaitNode(ToolInvocation runtime, java.util.concurrent.CompletableFuture<String> result)
            throws Exception {
        try {
            String evidence = result.get();
            runtime.checkActive();
            return evidence;
        } catch (InterruptedException interrupted) {
            result.cancel(false);
            throw interrupted;
        } catch (ExecutionException failure) {
            runtime.checkActive();
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof ApplicationFailure application && "RetryableCognitiveModel".equals(application.getType()))
                    throw new java.io.IOException("认知节点的模型请求暂时不可用", application);
            }
            throw new IllegalStateException("远程节点执行失败", failure.getCause());
        }
    }

    @WorkflowInterface
    public interface ResearchEvidenceWorkflow {
        @WorkflowMethod
        String run(String runId, String token, String query);
    }

    @ActivityInterface
    public interface ResearchEvidenceActivity {
        String collectResearchEvidence(String runId, String token, String query);
    }

    public static class ResearchEvidenceWorkflowImpl implements ResearchEvidenceWorkflow {
        private final ResearchEvidenceActivity evidence = Workflow.newActivityStub(ResearchEvidenceActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(60))
                        .setScheduleToCloseTimeout(Duration.ofMinutes(3)).build());

        @Override
        public String run(String runId, String token, String query) {
            return evidence.collectResearchEvidence(runId, token, query);
        }
    }

    public record ResearchEvidenceActivityImpl(GenerationService generation) implements ResearchEvidenceActivity {
        @Override
        public String collectResearchEvidence(String runId, String token, String query) {
            try {
                return generation.executeResearchEvidence(runId, token, query);
            } catch (Store.StaleExecution expired) {
                throw ApplicationFailure.newNonRetryableFailure("Research run attempt is no longer active", "StaleExecution");
            } catch (IllegalArgumentException configuration) {
                throw ApplicationFailure.newNonRetryableFailure("Research evidence configuration is invalid", "InvalidResearchSource");
            } catch (Exception failure) {
                throw Activity.wrap(failure);
            }
        }
    }

    @WorkflowInterface
    public interface ResearchModelWorkflow {
        @WorkflowMethod
        String run(String runId, String token, String node, String input);
    }

    @ActivityInterface
    public interface ResearchModelActivity {
        String executeResearchModelNode(String runId, String token, String node, String input);
    }

    public static class ResearchModelWorkflowImpl implements ResearchModelWorkflow {
        private final ResearchModelActivity activity = Workflow.newActivityStub(ResearchModelActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(31))
                        .setHeartbeatTimeout(Duration.ofSeconds(15))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build()).build());

        @Override
        public String run(String runId, String token, String node, String input) {
            return activity.executeResearchModelNode(runId, token, node, input);
        }
    }

    public record ResearchModelActivityImpl(Store store, GenerationService generation) implements ResearchModelActivity {
        @Override
        public String executeResearchModelNode(String runId, String token, String node, String input) {
            try {
                return withTokenHeartbeat(store, runId, token,
                        () -> generation.executeResearchModelNode(runId, token, node, input));
            } catch (Store.StaleExecution expired) {
                throw ApplicationFailure.newNonRetryableFailure("Research run attempt is no longer active", "StaleExecution");
            } catch (IllegalArgumentException | Store.ModelCallLimit invalid) {
                throw ApplicationFailure.newNonRetryableFailure("Research model node rejected its input or budget", "InvalidResearchNode");
            } catch (Exception failure) {
                throw Activity.wrap(failure);
            }
        }
    }

    String agentNode(ToolInvocation runtime, AgentNodeInput input) throws Exception {
        runtime.checkActive();
        String identity = input.token() + "\n" + input.aspect()
                + (input.cognitive() ? "\n" + input.instructions() : "");
        String id = input.runId() + "-agent-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        var parent = childParent(runtime.rid);
        if (parent != null)
            return awaitNode(runtime, parent.startUpdate(UpdateOptions.newBuilder(String.class)
                    .setUpdateName("executeNode").setUpdateId(id)
                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED).build(),
                    new NodeRequest(NodeKind.AGENT, id, input.token(), null, null, input)).getResultAsync());
        var workflow = client.newWorkflowStub(AgentNodeWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(id).setTaskQueue(agentsQueue)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(65))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(workflow::run, input);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            // The same run attempt already submitted this role.
        }
        return awaitNode(runtime, WorkflowStub.fromTyped(workflow));
    }

    public record AgentNodeInput(String runId, String token, String nodeId, int budget, String aspect,
                                 String taskId, String query, Map<String, String> dependencies,
                                 String instructions, WorkflowTemplate.Task task, int outputDivisor) {
        public AgentNodeInput(String runId, String token, String nodeId, int budget, String aspect,
                              String taskId, String query, Map<String, String> dependencies,
                              String instructions, WorkflowTemplate.Task task) {
            this(runId, token, nodeId, budget, aspect, taskId, query, dependencies, instructions, task, 8);
        }

        boolean cognitive() {
            return nodeId != null && taskId == null && aspect.startsWith("cognitive.");
        }
    }

    @WorkflowInterface
    public interface AgentNodeWorkflow {
        @WorkflowMethod
        String run(AgentNodeInput input);
    }

    @ActivityInterface
    public interface AgentNodeActivity {
        String executeAgentNode(AgentNodeInput input);
    }

    public static class AgentNodeWorkflowImpl implements AgentNodeWorkflow {
        private final AgentNodeActivity activity = Workflow.newActivityStub(AgentNodeActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(31))
                        .setHeartbeatTimeout(Duration.ofSeconds(15))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build()).build());

        @Override
        public String run(AgentNodeInput input) {
            return activity.executeAgentNode(input);
        }
    }

    public record AgentNodeActivityImpl(Store store, GenerationService generation) implements AgentNodeActivity {
        @Override
        public String executeAgentNode(AgentNodeInput input) {
            try {
                return withTokenHeartbeat(store, input.runId(), input.token(), () -> generation.executeAgentNode(input));
            } catch (Store.StaleExecution expired) {
                throw ApplicationFailure.newNonRetryableFailure("Agent run attempt is no longer active", "StaleExecution");
            } catch (TextModelTransport.ProviderFailure | java.io.IOException modelFailure) {
                if (!input.cognitive()) throw Activity.wrap(modelFailure);
                boolean retryable = modelFailure instanceof TextModelTransport.ProviderFailure provider ? provider.retryable()
                        : !(modelFailure instanceof com.fasterxml.jackson.core.JsonProcessingException);
                throw ApplicationFailure.newNonRetryableFailure("Cognitive model request failed",
                        retryable ? "RetryableCognitiveModel" : "InvalidCognitiveModel");
            } catch (IllegalArgumentException | Store.ModelCallLimit invalid) {
                throw ApplicationFailure.newNonRetryableFailure("Agent node rejected its input or budget", "InvalidAgentNode");
            } catch (Exception failure) {
                throw Activity.wrap(failure);
            }
        }
    }

    Instant syncSchedule(String id, String version, String cron, String zone, String status) {
        String scheduleId = "atoms-schedule-" + id;
        var handle = scheduleClient.getHandle(scheduleId);
        if ("DELETED".equals(status)) {
            try {
                handle.delete();
            } catch (ScheduleException error) {
                if (Status.fromThrowable(error).getCode() != Status.Code.NOT_FOUND) throw error;
            }
            return null;
        }
        boolean paused = "PAUSED".equals(status);
        var desired = Schedule.newBuilder()
                .setSpec(ScheduleSpec.newBuilder().setCronExpressions(java.util.List.of(cron))
                        .setTimeZoneName(zone).build())
                .setAction(ScheduleActionStartWorkflow.newBuilder().setWorkflowType(ScheduledTrigger.class)
                        .setOptions(WorkflowOptions.newBuilder().setTaskQueue(queue)
                                .setWorkflowId("atoms-scheduled-trigger-" + id).build())
                        .setArguments(id, version).build())
                .setPolicy(SchedulePolicy.newBuilder().setCatchupWindow(Duration.ofHours(1))
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP).build())
                .setState(ScheduleState.newBuilder().setPaused(paused).build()).build();
        try {
            handle.update(input -> new ScheduleUpdate(desired));
        } catch (ScheduleException error) {
            if (Status.fromThrowable(error).getCode() != Status.Code.NOT_FOUND) throw error;
            try {
                scheduleClient.createSchedule(scheduleId, desired, ScheduleOptions.newBuilder().build());
            } catch (ScheduleAlreadyRunningException concurrentCreate) {
                handle.update(input -> new ScheduleUpdate(desired));
            }
        }
        return paused ? null : handle.describe().getInfo().getNextActionTimes().stream().findFirst().orElse(null);
    }

    @WorkflowInterface
    public interface ScheduledTrigger {
        @WorkflowMethod
        void run(String id, String version);
    }

    @ActivityInterface
    public interface ScheduledActivities {
        void enqueueScheduledRun(String id, String version, long occurrence);
    }

    public static class ScheduledTriggerImpl implements ScheduledTrigger {
        private static final SearchAttributeKey<java.time.OffsetDateTime> SCHEDULED_AT =
                SearchAttributeKey.forOffsetDateTime("TemporalScheduledStartTime");
        private final ScheduledActivities activity = Workflow.newActivityStub(ScheduledActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

        @Override
        public void run(String id, String version) {
            var scheduledAt = Workflow.getTypedSearchAttributes().get(SCHEDULED_AT);
            long occurrence = scheduledAt == null ? Workflow.currentTimeMillis() / 1000 : scheduledAt.toEpochSecond();
            activity.enqueueScheduledRun(id, version, occurrence);
        }
    }

    public record ScheduledEnqueue(ObjectProvider<ScheduledTasks> schedules) implements ScheduledActivities {
        @Override
        public void enqueueScheduledRun(String id, String version, long occurrence) {
            schedules.getObject().enqueueTemporal(id, version, occurrence);
        }
    }

    public Map<String, Object> timeline(String id, String runId, boolean full) {
        try (var history = client.streamHistory(id, runId)) {
            var events = history.filter(event -> full || !event.getEventType().name()
                            .startsWith("EVENT_TYPE_WORKFLOW_TASK_"))
                    .map(event -> {
                        var timestamp = event.getEventTime();
                        return Map.of("workflow_id", id, "seq", event.getEventId(),
                                "type", event.getEventType().name().replaceFirst("^EVENT_TYPE_", ""),
                                "timestamp", Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString());
                    }).toList();
            return Map.of("workflow_id", id, "events", events, "stats", Map.of("count", events.size()));
        } catch (WorkflowNotFoundException missing) {
            throw Store.error(404, "Temporal 工作流历史不存在");
        }
    }

    public enum NodeKind { EVIDENCE, RESEARCH_MODEL, AGENT }

    public record NodeRequest(NodeKind kind, String workflowId, String token, String node, String input,
                              AgentNodeInput agent) {}

    @WorkflowInterface
    public interface TaskWorkflow {
        @WorkflowMethod
        void run(String id);

        @QueryMethod
        boolean nativeChildren();

        @SignalMethod
        void controlChanged();

        @UpdateMethod
        String executeNode(NodeRequest request);
    }

    public enum TaskSlice { DONE, WAIT_FOR_CONTROL, NEXT_ACTION }

    @ActivityInterface
    public interface TaskActivities {
        boolean executeUntilControlBoundary(String id);

        TaskSlice executeTaskSlice(String id);
    }

    public static class TaskWorkflowImpl implements TaskWorkflow {
        private final TaskActivities task = Workflow.newActivityStub(TaskActivities.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofHours(2))
                .setHeartbeatTimeout(Duration.ofSeconds(15))
                .build());

        private boolean nativeChildren;
        private long controlRevision;
        private final LinkedHashSet<CancellationScope> runningNodes = new LinkedHashSet<>();

        @Override
        public void run(String id) {
            nativeChildren = Workflow.getVersion("native-node-children", Workflow.DEFAULT_VERSION, 1) == 1;
            boolean sliced = Workflow.getVersion("agent-action-activities", Workflow.DEFAULT_VERSION, 1) == 1;
            boolean signaledControl = Workflow.getVersion("agent-control-wakeup", Workflow.DEFAULT_VERSION, 1) == 1;
            for (int i = 0; i < 120; i++) {
                long before = controlRevision;
                TaskSlice result = sliced ? task.executeTaskSlice(id)
                        : task.executeUntilControlBoundary(id) ? TaskSlice.DONE : TaskSlice.WAIT_FOR_CONTROL;
                if (result == TaskSlice.DONE) {
                    if (nativeChildren) closeNodes();
                    return;
                }
                if (result == TaskSlice.WAIT_FOR_CONTROL) {
                    if (signaledControl) Workflow.await(Duration.ofSeconds(15), () -> controlRevision != before);
                    else Workflow.sleep(Duration.ofSeconds(3));
                }
            }
            if (nativeChildren) closeNodes();
            Workflow.continueAsNew(id);
        }

        @Override
        public void controlChanged() {
            controlRevision++;
        }

        @Override
        public boolean nativeChildren() {
            return nativeChildren;
        }

        @Override
        public String executeNode(NodeRequest request) {
            if (!nativeChildren) throw ApplicationFailure.newNonRetryableFailure(
                    "Native child workflows are disabled for this history", "LegacyTaskWorkflow");
            var result = Workflow.<String>newPromise();
            var scope = Workflow.newCancellationScope(() -> result.complete(runChild(request)));
            runningNodes.add(scope);
            try {
                scope.run();
                return result.get();
            } finally {
                runningNodes.remove(scope);
            }
        }

        private void closeNodes() {
            // An in-flight update can belong to an earlier, superseded execution token.
            runningNodes.forEach(CancellationScope::cancel);
            Workflow.await(runningNodes::isEmpty);
        }

        private String runChild(NodeRequest request) {
            var parent = Workflow.getInfo();
            boolean evidence = request.kind() == NodeKind.EVIDENCE;
            var options = ChildWorkflowOptions.newBuilder().setWorkflowId(request.workflowId())
                    .setTaskQueue(parent.getTaskQueue() + (request.kind() == NodeKind.AGENT ? "-agents" : "-research-evidence"))
                    .setWorkflowExecutionTimeout(Duration.ofMinutes(evidence ? 4 : 65))
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .setCancellationType(ChildWorkflowCancellationType.TRY_CANCEL)
                    .build();
            return switch (request.kind()) {
                case EVIDENCE -> Workflow.newChildWorkflowStub(ResearchEvidenceWorkflow.class, options)
                        .run(parent.getWorkflowId(), request.token(), request.node());
                case RESEARCH_MODEL -> Workflow.newChildWorkflowStub(ResearchModelWorkflow.class, options)
                        .run(parent.getWorkflowId(), request.token(), request.node(), request.input());
                case AGENT -> Workflow.newChildWorkflowStub(AgentNodeWorkflow.class, options).run(request.agent());
            };
        }
    }

    public record TaskActivitiesImpl(Store store, GenerationService generation) implements TaskActivities {
        @Override
        public boolean executeUntilControlBoundary(String id) {
            return execute(id, false) == TaskSlice.DONE;
        }

        @Override
        public TaskSlice executeTaskSlice(String id) {
            return execute(id, true);
        }

        private TaskSlice execute(String id, boolean sliced) {
            var run = store.activeRunForTemporal(id);
            if (run == null) return TaskSlice.DONE;

            String token;
            try {
                token = store.claimForTemporal(id, run.owner(), !sliced);
            } catch (Store.StaleExecution controlChanged) {
                return store.activeRunForTemporal(id) == null ? TaskSlice.DONE : TaskSlice.WAIT_FOR_CONTROL;
            }

            try {
                return withTokenHeartbeat(store, id, token, () -> {
                    generation.execute(id, run.projectId(), run.owner(), run.prompt(), run.baseVersion(),
                            run.mode(), run.model(), token, sliced);
                    if (store.activeRunForTemporal(id) == null) return TaskSlice.DONE;
                    return sliced && store.hasExecutionToken(id, token)
                            ? TaskSlice.NEXT_ACTION : TaskSlice.WAIT_FOR_CONTROL;
                });
            } catch (Exception failure) {
                throw Activity.wrap(failure);
            }
        }

    }

    private static <T> T withTokenHeartbeat(Store store, String id, String token, Callable<T> action) throws Exception {
        var context = Activity.getExecutionContext();
        var activityThread = Thread.currentThread();
        var heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("atoms-activity-heartbeat").daemon().factory());
        try {
            context.heartbeat(null);
            heartbeat.scheduleWithFixedDelay(() -> {
                context.heartbeat(null);
                boolean current;
                try {
                    current = store.hasExecutionToken(id, token);
                } catch (org.springframework.dao.DataAccessException unavailable) {
                    return;
                }
                if (!current) {
                    synchronized (heartbeat) {
                        if (!heartbeat.isShutdown()) activityThread.interrupt();
                    }
                }
            }, 3, 3, TimeUnit.SECONDS);
            return action.call();
        } finally {
            synchronized (heartbeat) {
                heartbeat.shutdownNow();
            }
        }
    }
}
