package dev.atoms.web;

import dev.atoms.agent.TaskApprovalPolicy;
import dev.atoms.agent.GenerationService;
import dev.atoms.agent.GatewayAgents;
import dev.atoms.agent.ScheduledTasks;
import dev.atoms.agent.TemporalTaskRuntime;
import dev.atoms.agent.model.ModelCatalog;
import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.pattern.PatternRegistry;
import dev.atoms.agent.pattern.LearningRouter;
import dev.atoms.agent.persistence.Store;
import dev.atoms.agent.skill.Skill;
import dev.atoms.agent.skill.SkillCatalog;
import dev.atoms.agent.tool.ToolMetadata;
import dev.atoms.agent.pattern.WorkflowTemplates;
import dev.atoms.workspace.GatewayMemoryFiles;
import dev.atoms.workspace.SourceFiles;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.nio.charset.StandardCharsets;

/**
 * 接收项目和任务请求，并提供版本、导出及 SSE 执行进度接口。
 *
 * <p>示例：GET /api/runs/{id}/events 订阅任务状态。</p>
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    final Store store;
    final GenerationService generation;
    final ModelClient model;
    final ModelCatalog catalog;
    final SkillCatalog skills;
    final WorkflowTemplates templates;
    final LearningRouter learningRouter;
    final ScheduledTasks scheduledTasks;
    final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    private final GatewayTaskEvents gatewayEvents;
    private final GatewayAgents agents;
    private final GatewayMemoryFiles memory;
    private final TaskApprovalPolicy approvalPolicy;

    ApiController(Store store, GenerationService generation, ModelClient model, ModelCatalog catalog, SkillCatalog skills,
                  WorkflowTemplates templates, LearningRouter learningRouter, ScheduledTasks scheduledTasks,
                  GatewayTaskEvents gatewayEvents, TaskApprovalPolicy approvalPolicy, GatewayAgents agents, GatewayMemoryFiles memory) {
        this.catalog = catalog;
        this.store = store;
        this.generation = generation;
        this.model = model;
        this.skills = skills;
        this.templates = templates;
        this.learningRouter = learningRouter;
        this.scheduledTasks = scheduledTasks;
        this.gatewayEvents = gatewayEvents;
        this.agents = agents;
        this.memory = memory;
        this.approvalPolicy = approvalPolicy;
        scheduler.setPoolSize(2);
        scheduler.initialize();
    }

    @PreDestroy
    void close() {
        scheduler.shutdown();
    }

    String owner(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute("owner") == null) session.setAttribute("owner", Store.id());
            return (String) session.getAttribute("owner");
        }
    }

    /**
     * 接收新项目的标题。
     *
     * <p>示例：<code>new ProjectInput("记账本")</code>。</p>
     */
    record ProjectInput(String title) {
    }

    /**
     * 接收任务需求、基准版本、模型及执行策略。
     *
     * <p>示例：提交构建需求时可指定模板 project_review。</p>
     */
    record RunInput(String prompt, String baseVersion, String mode, String model, String pattern, String skill,
                    @JsonAlias("template_name") String template,
                    @JsonAlias("template_version") String templateVersion,
                    @JsonAlias("disable_ai") boolean requireTemplate) {
    }

    /** 接收 Shannon 单任务请求，例如在已有 session_id 上继续提问。 */
    record TaskInput(String query, @JsonProperty("session_id") String sessionId,
                     Map<String, Object> context, String mode, String skill,
                     @JsonProperty("model_override") String modelOverride,
                     @JsonAlias("template_name") String template, @JsonAlias("template_version") String templateVersion,
                     @JsonAlias("disable_ai") boolean requireTemplate,
                     @JsonProperty("research_strategy") String researchStrategy) {}

    private String gatewayOwner(HttpSession session) {
        var request = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        Object tenant = request.getAttribute("gatewayOwner", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return tenant instanceof String key ? key : owner(session);
    }

    @PostMapping("/v1/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> submitTask(@RequestBody TaskInput input, HttpSession session,
                                   jakarta.servlet.http.HttpServletRequest request) {
        if (input.query() == null || input.query().isBlank() || input.query().length() > 10000)
            throw Store.error(400, "query 须为1～10000字");
        var context = new LinkedHashMap<String, Object>();
        if (input.context() != null) context.putAll(input.context());
        context.remove("workflow_model_completion");
        if (input.researchStrategy() != null) {
            if (!Set.of("quick", "standard", "deep", "academic").contains(input.researchStrategy()))
                throw Store.error(400, "research_strategy 须为 quick、standard、deep 或 academic");
            context.put("research_strategy", input.researchStrategy());
        }
        if (store.encode(context).length() > 16000) throw Store.error(400, "context 超过长度限制");
        String name = input.template();
        if (name == null || name.isBlank()) name = context.get("template") instanceof String value
                ? value : context.get("template_name") instanceof String alias ? alias : null;
        String version = input.templateVersion();
        if (version == null && context.get("template_version") instanceof String value) version = value;
        boolean required = input.requireTemplate() || Boolean.TRUE.equals(context.get("disable_ai"));
        var template = templates.route(name, version, required);
        String mode = input.mode() == null || input.mode().isBlank() ? "simple" : input.mode();
        if (template == null && !Set.of("simple", "standard", "complex", "dag", "auto", "research").contains(mode))
            throw Store.error(400, "当前任务入口支持 simple、standard、complex、dag、auto 和 research");
        boolean research = template == null && ("research".equals(mode) || Boolean.TRUE.equals(context.get("force_research")));
        String pattern = template != null ? "reflection" : switch (mode) {
            case "complex", "dag" -> "dag";
            case "standard" -> "react";
            case "auto" -> "auto";
            default -> null;
        };
        String owner = gatewayOwner(session);
        if (input.sessionId() != null && !input.sessionId().isBlank()) store.gatewaySession(input.sessionId(), owner);
        var project = input.sessionId() == null || input.sessionId().isBlank()
                ? store.createGatewaySession(owner) : store.project(input.sessionId(), owner);
        String id = (String) project.get("id");
        @SuppressWarnings("unchecked")
        var queued = (Map<String, Object>) run(id, new RunInput(input.query(), (String) project.get("current_version"),
                research ? "research" : pattern == null ? "chat" : "build", input.modelOverride(), pattern, input.skill(),
                template == null ? null : name, template == null ? null : version, required), session, owner, context,
                GatewayMemoryController.taskScope(request));
        var result = new LinkedHashMap<String, Object>(Map.of("task_id", queued.get("id"), "session_id", id,
                "status", "TASK_STATUS_QUEUED", "created_at", queued.get("createdAt")));
        if ("AWAITING_APPROVAL".equals(queued.get("status"))) {
            result.put("awaiting_approval", true);
            result.put("approval_id", queued.get("id"));
            if (queued.get("approvalReason") instanceof String reason) result.put("approval_reason", reason);
        }
        return result;
    }

    @PostMapping("/v1/tasks/stream")
    ResponseEntity<Map<String, String>> submitTaskForStreaming(@RequestBody TaskInput input, HttpSession session,
                                                               jakarta.servlet.http.HttpServletRequest request) {
        var task = submitTask(input, session, request);
        String id = (String) task.get("task_id");
        String streamUrl = "/api/v1/stream/sse?workflow_id=" + id;
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Workflow-ID", id)
                .header("X-Session-ID", (String) task.get("session_id"))
                .header("Link", "<" + streamUrl + ">; rel=stream")
                .body(Map.of("workflow_id", id, "task_id", id, "stream_url", streamUrl));
    }

    @GetMapping("/v1/tasks/{id}")
    Object taskStatus(@PathVariable String id, HttpSession session) {
        return store.simpleTask(id, gatewayOwner(session));
    }

    @GetMapping("/v1/tasks/{id}/review")
    ResponseEntity<Map<String, Object>> getTaskReview(@PathVariable String id, HttpSession session) {
        String owner = gatewayOwner(session);
        var task = store.simpleTask(id, owner);
        if (!Boolean.TRUE.equals(task.get("awaiting_approval"))) throw Store.error(404, "任务暂无待审批记录");
        var run = store.run(id, owner);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "pending");
        result.put("round", 0);
        result.put("version", 1);
        result.put("current_plan", Objects.toString(run.get("plan"), ""));
        result.put("rounds", List.of());
        result.put("query", task.get("query"));
        if (task.get("approval_reason") instanceof String reason) result.put("approval_reason", reason);
        return ResponseEntity.ok().eTag("1").body(result);
    }

    /** 接收模板审批动作，例如 action 为 approve。 */
    record TaskReview(String action) {}

    @PostMapping("/v1/tasks/{id}/review")
    Object reviewTask(@PathVariable String id, @RequestBody TaskReview input, HttpSession session) {
        if (!"approve".equals(input.action())) throw Store.error(400, "当前任务审批支持 approve");
        String owner = gatewayOwner(session);
        store.simpleTask(id, owner);
        generation.approve(id, owner, true);
        return store.simpleTask(id, owner);
    }

    record ApprovalDecision(
            @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("approval_id") String approvalId,
            @JsonProperty("run_id") String runId,
            Boolean approved) {}

    @PostMapping("/v1/approvals/decision")
    Object submitApprovalDecision(@RequestBody ApprovalDecision input, HttpSession session) {
        if (input.workflowId() == null || input.workflowId().isBlank()
                || input.approvalId() == null || input.approvalId().isBlank() || input.approved() == null)
            throw Store.error(400, "须提供 workflow_id、approval_id 和 approved");
        String owner = gatewayOwner(session);
        store.simpleTask(input.workflowId(), owner);
        if (input.runId() != null && !input.runId().isEmpty() && !input.workflowId().equals(input.runId()))
            throw Store.error(404, "审批请求不存在");
        generation.approve(input.workflowId(), owner, input.approved(), input.approvalId());
        return Map.of("status", "sent", "success", true, "message", "Approval decision recorded",
                "workflow_id", input.workflowId(), "approval_id", input.approvalId(),
                "run_id", Objects.toString(input.runId(), ""));
    }

    @GetMapping("/v1/sessions")
    Object sessions(HttpSession session) {
        var sessions = store.gatewaySessions(gatewayOwner(session), "");
        return Map.of("sessions", sessions, "total_count", sessions.size());
    }

    @GetMapping("/v1/sessions/{id}")
    Object session(@PathVariable String id, HttpSession session) {
        return store.gatewaySession(id, gatewayOwner(session));
    }

    @PatchMapping("/v1/sessions/{id}")
    Object updateSession(@PathVariable String id, @RequestBody ProjectInput input, HttpSession session) {
        String title = input.title() == null ? "" : input.title().replaceAll("[\\p{Cc}\\u200B\\uFEFF]", "").strip();
        if (title.isEmpty() || title.length() > 200) throw Store.error(400, "title 须为1～200字");
        return store.updateGatewaySession(id, gatewayOwner(session), title);
    }

    @DeleteMapping("/v1/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSession(@PathVariable String id, HttpSession session) {
        store.deleteGatewaySession(id, gatewayOwner(session));
    }

    @GetMapping("/v1/sessions/{id}/history")
    Object sessionHistory(@PathVariable String id, HttpSession session) {
        return store.gatewayHistory(id, gatewayOwner(session));
    }

    @GetMapping("/v1/sessions/{id}/events")
    Object sessionEvents(@PathVariable String id, @RequestParam(defaultValue = "") String limit,
                         @RequestParam(defaultValue = "") String offset, HttpSession session) {
        int pageSize = parseTaskPageParameter(limit, 10);
        if (pageSize < 1 || pageSize > 100) pageSize = 10;
        return store.gatewaySessionEvents(id, gatewayOwner(session), pageSize,
                Math.max(0, parseTaskPageParameter(offset, 0)));
    }

    record WorkspaceEntry(String name, String path,
                          @JsonProperty("is_dir") boolean directory,
                          @JsonProperty("size_bytes") int sizeBytes) {}

    @GetMapping("/v1/sessions/{id}/documents/search")
    Object searchWorkspaceDocuments(@PathVariable String id, @RequestParam String query, HttpSession session) {
        return Map.of("hits", store.searchGatewayDocuments(id, gatewayOwner(session), query));
    }

    @GetMapping("/v1/sessions/{id}/files")
    Object listWorkspaceFiles(@PathVariable String id, @RequestParam(defaultValue = "") String path,
                              HttpSession session) {
        if (!path.isEmpty()) validateWorkspacePath(path);
        var files = store.gatewayWorkspaceFiles(id, gatewayOwner(session));
        String prefix = path.isEmpty() ? "" : path + "/";
        var entries = new TreeMap<String, WorkspaceEntry>();
        files.forEach((file, content) -> {
            if (!file.startsWith(prefix)) return;
            String relative = file.substring(prefix.length());
            int separator = relative.indexOf('/');
            if (separator < 0) {
                entries.put(relative, new WorkspaceEntry(relative, file, false,
                        content.getBytes(StandardCharsets.UTF_8).length));
            } else {
                String directory = relative.substring(0, separator);
                entries.putIfAbsent(directory, new WorkspaceEntry(directory, prefix + directory, true, 0));
            }
        });
        if (!path.isEmpty() && entries.isEmpty()) throw Store.error(404, "目录不存在");
        return Map.of("success", true, "files", entries.values());
    }

    @GetMapping("/v1/sessions/{id}/files/{*path}")
    Object downloadWorkspaceFile(@PathVariable String id, @PathVariable String path, HttpSession session) {
        String file = path.startsWith("/") ? path.substring(1) : path;
        validateWorkspacePath(file);
        var files = store.gatewayWorkspaceFiles(id, gatewayOwner(session));
        String content = files.get(file);
        if (content == null) {
            if (files.keySet().stream().anyMatch(name -> name.startsWith(file + "/")))
                throw Store.error(400, "该路径是目录");
            throw Store.error(404, "文件不存在");
        }
        return Map.of("success", true, "content", content, "content_type", "text/plain; charset=utf-8",
                "size_bytes", content.getBytes(StandardCharsets.UTF_8).length);
    }

    private static void validateWorkspacePath(String path) {
        if (path.length() > 240 || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.codePoints().anyMatch(Character::isISOControl)
                || Arrays.stream(path.split("/", -1))
                .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals("..")))
            throw Store.error(400, "须提供有效的工作区相对路径");
    }

    @PostMapping("/v1/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    Object createSchedule(@RequestBody ScheduledTasks.Input input, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        return scheduledTasks.create(gatewayOwner(session), input, DaemonApprovalController.requester(request));
    }

    @GetMapping("/v1/schedules")
    Object listSchedules(@RequestParam(defaultValue = "1") int page,
                         @RequestParam(name = "page_size", defaultValue = "50") int size,
                         @RequestParam(defaultValue = "") String status, HttpSession session) {
        return scheduledTasks.list(gatewayOwner(session), Math.max(1, page), schedulePageSize(size), status);
    }

    @GetMapping("/v1/schedules/{id}")
    Object schedule(@PathVariable String id, HttpSession session) {
        return scheduledTasks.get(id, gatewayOwner(session));
    }

    @PutMapping("/v1/schedules/{id}")
    Object updateSchedule(@PathVariable String id, @RequestBody ScheduledTasks.Input input, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        return scheduledTasks.update(id, gatewayOwner(session), input, DaemonApprovalController.requester(request));
    }

    @PostMapping("/v1/schedules/{id}/pause")
    Object pauseSchedule(@PathVariable String id, HttpSession session) {
        return scheduledTasks.changeStatus(id, gatewayOwner(session), "PAUSED");
    }

    @PostMapping("/v1/schedules/{id}/resume")
    Object resumeSchedule(@PathVariable String id, HttpSession session) {
        return scheduledTasks.changeStatus(id, gatewayOwner(session), "ACTIVE");
    }

    @DeleteMapping("/v1/schedules/{id}")
    Object deleteSchedule(@PathVariable String id, HttpSession session) {
        return scheduledTasks.changeStatus(id, gatewayOwner(session), "DELETED");
    }

    @GetMapping("/v1/schedules/{id}/runs")
    Object scheduleRuns(@PathVariable String id, @RequestParam(defaultValue = "1") int page,
                        @RequestParam(name = "page_size", defaultValue = "50") int size, HttpSession session) {
        return scheduledTasks.runs(id, gatewayOwner(session), Math.max(1, page), schedulePageSize(size));
    }

    private static int schedulePageSize(int size) {
        return size < 1 || size > 100 ? 50 : size;
    }

    @GetMapping("/v1/skills")
    Object gatewaySkills(@RequestParam(defaultValue = "") String category, HttpSession session) {
        gatewayOwner(session);
        var available = skills.available();
        var latest = new LinkedHashMap<String, Skill>();
        for (var skill : available) {
            if (category.isEmpty() || category.equals(skill.category())) latest.putIfAbsent(skill.name(), skill);
        }
        return Map.of("skills", latest.values().stream().map(Skill::summary).toList(), "count", latest.size(),
                "categories", available.stream().map(Skill::category).distinct().sorted().toList());
    }

    @GetMapping("/v1/skills/{reference}")
    Object gatewaySkill(@PathVariable String reference, HttpSession session) {
        gatewayOwner(session);
        var skill = skills.available().stream()
                .filter(entry -> entry.name().equals(reference) || entry.reference().equals(reference))
                .findFirst().orElseThrow(() -> Store.error(404, "技能不存在"));
        return Map.of("skill", skill);
    }

    @GetMapping("/v1/skills/{name}/versions")
    Object gatewaySkillVersions(@PathVariable String name, HttpSession session) {
        gatewayOwner(session);
        var versions = skills.available().stream().filter(skill -> skill.name().equals(name))
                .map(Skill::summary).toList();
        if (versions.isEmpty()) throw Store.error(404, "技能不存在");
        return Map.of("name", name, "versions", versions, "count", versions.size());
    }

    @GetMapping("/v1/tools")
    Object gatewayTools(@RequestParam(defaultValue = "") String category, HttpSession session) {
        gatewayOwner(session);
        var result = new ArrayList<Map<String, Object>>();
        for (var tool : model.toolCatalog()) {
            var metadata = (ToolMetadata) tool.get("metadata");
            if (!metadata.dangerous() && (category.isEmpty() || category.equals(metadata.category()))) {
                result.add(Map.of("name", metadata.name(), "description", metadata.description(),
                        "parameters", tool.get("parameters")));
            }
        }
        return result;
    }

    @GetMapping("/v1/agents")
    Object gatewayAgents() {
        var available = agents.list();
        return Map.of("agents", available, "count", available.size());
    }

    @GetMapping("/v1/agents/{id}")
    Object gatewayAgent(@PathVariable String id) { return agents.get(id); }

    record AgentInput(Map<String, Object> input, @JsonProperty("session_id") String sessionId) {}

    @PostMapping("/v1/agents/{id}")
    ResponseEntity<Map<String, Object>> runGatewayAgent(@PathVariable String id, @RequestBody AgentInput request,
                                                       HttpSession session) {
        var input = request.input() == null ? Map.<String, Object>of() : request.input();
        String tool = agents.validate(id, input);
        String owner = gatewayOwner(session);
        var submitted = queueGatewayTool(owner, request.sessionId(), "Execute agent " + id, id, tool, input);
        var result = new LinkedHashMap<String, Object>();
        result.put("task_id", submitted.id());
        result.put("agent_id", id);
        result.put("status", "TASK_STATUS_QUEUED");
        result.put("created_at", submitted.createdAt());
        if (submitted.approval() != null) result.putAll(toolApproval(submitted));
        return ResponseEntity.accepted().header("X-Workflow-ID", submitted.id()).header("X-Session-ID", submitted.session()).body(result);
    }

    private record QueuedGatewayTool(String id, String session, Object createdAt, String approval) {}

    private QueuedGatewayTool queueGatewayTool(String owner, String session, String prompt, String agent,
                                               String tool, Map<String, Object> input) {
        String approval = approvalPolicy.requirement(owner, prompt, GatewayAgents.MODE, tool, "react",
                null, null, List.of(tool));
        String pid = session;
        if (pid == null || pid.isBlank()) pid = (String) store.createGatewaySession(owner).get("id");
        store.gatewaySession(pid, owner);
        String base = (String) store.project(pid, owner).get("current_version");
        var context = Map.<String, Object>of("agent", agent, "agent_tool", tool, "agent_input", input);
        var run = store.queue(pid, owner, prompt, base, GatewayAgents.MODE, tool, "react", null, null, context, approval);
        String id = (String) run.get("id");
        generation.submit(id, pid, owner, prompt, base, GatewayAgents.MODE, tool);
        return new QueuedGatewayTool(id, pid, run.get("createdAt"), approval);
    }

    private static Map<String, Object> toolApproval(QueuedGatewayTool task) {
        return Map.of("task_id", task.id(), "awaiting_approval", true, "approval_id", task.id(),
                "approval_reason", task.approval());
    }

    record ToolInput(Map<String, Object> arguments, @JsonProperty("session_id") String sessionId) {}

    @PostMapping("/v1/tools/{name}/execute")
    Object executeGatewayTool(@PathVariable String name, @RequestBody ToolInput input,
                              jakarta.servlet.http.HttpServletRequest request) {
        if (!(request.getAttribute("gatewayOwner") instanceof String owner))
            throw Store.error(401, "直接工具执行需要 Gateway 身份凭据");
        var arguments = input.arguments() == null ? Map.<String, Object>of() : input.arguments();
        agents.validateDirectTool(name, arguments);
        var task = queueGatewayTool(owner, input.sessionId(), "Execute tool " + name, "", name, arguments);
        if (task.approval() != null)
            return toolResponse(409, task, toolApproval(task));
        return waitForTool(owner, task);
    }

    private static ResponseEntity<Map<String, Object>> toolResponse(int status, QueuedGatewayTool task, Map<String, Object> body) {
        return ResponseEntity.status(status).header("X-Workflow-ID", task.id())
                .header("X-Session-ID", task.session()).body(body);
    }

    private org.springframework.web.context.request.async.DeferredResult<ResponseEntity<Map<String, Object>>> waitForTool(
            String owner, QueuedGatewayTool task) {
        var timeout = toolResponse(504, task, Map.of("success", false, "error", "等待工具结果超时", "task_id", task.id()));
        var result = new org.springframework.web.context.request.async.DeferredResult<ResponseEntity<Map<String, Object>>>(
                60_000L, timeout);
        var worker = Thread.ofVirtual().unstarted(() -> {
            try {
                while (!result.isSetOrExpired()) {
                    var current = store.simpleTask(task.id(), owner);
                    String status = (String) current.get("status");
                    if ("TASK_STATUS_COMPLETED".equals(status)) {
                        String output = Objects.toString(current.get("result"), "");
                        result.setResult(toolResponse(200, task, Map.of("success", true, "output", output, "text", output)));
                        return;
                    }
                    if (Set.of("TASK_STATUS_FAILED", "TASK_STATUS_CANCELLED", "TASK_STATUS_TIMEOUT").contains(status)) {
                        result.setResult(toolResponse(200, task, Map.of("success", false,
                                "error", Objects.toString(current.get("error"), status))));
                        return;
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                result.setErrorResult(failure);
            }
        });
        result.onCompletion(worker::interrupt);
        result.onTimeout(worker::interrupt);
        worker.start();
        return result;
    }

    @GetMapping("/v1/tools/{name}")
    Object gatewayTool(@PathVariable String name, HttpSession session) {
        gatewayOwner(session);
        for (var tool : model.toolCatalog()) {
            var metadata = (ToolMetadata) tool.get("metadata");
            if (!metadata.name().equals(name)) continue;
            if (metadata.dangerous()) throw Store.error(403, "工具不可通过 Gateway 查询");
            return Map.of("name", metadata.name(), "description", metadata.description(),
                    "category", metadata.category(), "version", metadata.version(), "parameters", tool.get("parameters"),
                    "timeout_seconds", metadata.timeoutSeconds(), "cost_per_use", 0);
        }
        throw Store.error(404, "工具不存在");
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TemporalTaskRuntime temporal;

    @GetMapping(value = "/v1/blob/{key}", produces = "text/plain;charset=UTF-8")
    Object gatewayBlob(@PathVariable String key, jakarta.servlet.http.HttpServletRequest request) {
        if (!(request.getAttribute("gatewayOwner") instanceof String owner))
            throw Store.error(401, "Blob 下载需要 Gateway 身份凭据");
        return store.gatewayBlob(owner, key);
    }

    @GetMapping("/v1/tasks/{id}/timeline")
    Object taskTimeline(@PathVariable String id,
                        @RequestParam(name = "run_id", defaultValue = "") String runId,
                        @RequestParam(defaultValue = "summary") String mode, HttpSession session) {
        store.simpleTask(id, gatewayOwner(session));
        if (temporal == null) throw Store.error(503, "当前部署未启用 Temporal");
        if (!Set.of("summary", "full").contains(mode)) throw Store.error(400, "timeline mode 无效");
        return temporal.timeline(id, runId, "full".equals(mode));
    }

    record SwarmMessage(String message) {}

    @GetMapping("/v1/swarm/{id}/board")
    Object gatewaySwarmBoard(@PathVariable String id, HttpSession session) {
        String owner = gatewayOwner(session);
        store.simpleTask(id, owner);
        var run = store.run(id, owner);
        Object board = run.get("swarmBoard");
        if (board == null) throw Store.error(404, "任务没有 Swarm 任务板");
        return Map.of("teams", board, "accepting_input", run.get("swarmAcceptingInput"));
    }

    @PostMapping("/v1/swarm/{id}/message")
    Object sendGatewaySwarmMessage(@PathVariable String id, @RequestBody SwarmMessage input, HttpSession session) {
        String owner = gatewayOwner(session);
        store.simpleTask(id, owner);
        return store.addSwarmHumanMessage(id, owner, input.message());
    }

    @PostMapping("/runs/{id}/swarm/message")
    Object sendWorkbenchSwarmMessage(@PathVariable String id, @RequestBody SwarmMessage input, HttpSession session) {
        return store.addSwarmHumanMessage(id, owner(session), input.message());
    }

    @GetMapping("/v1/tasks/{id}/control-state")
    Object taskControlState(@PathVariable String id, HttpSession session) {
        return store.gatewayControlState(id, gatewayOwner(session));
    }

    @GetMapping("/v1/tasks/{id}/events")
    Object taskEvents(@PathVariable String id, @RequestParam(defaultValue = "") String limit,
                      @RequestParam(defaultValue = "") String offset, HttpSession session) {
        int pageSize = parseTaskPageParameter(limit, 50);
        if (pageSize < 1 || pageSize > 200) pageSize = 50;
        return store.simpleTaskEvents(id, gatewayOwner(session), pageSize, Math.max(0, parseTaskPageParameter(offset, 0)));
    }

    @GetMapping(value = "/v1/tasks/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamTask(@PathVariable String id,
                          @RequestParam(name = "last_event_id", required = false) String cursor,
                          @RequestParam(defaultValue = "") String types,
                          @RequestHeader(name = "Last-Event-ID", required = false) String header, HttpSession session) {
        return streamEvents(id, cursor == null ? header : cursor, types, gatewayOwner(session));
    }

    @GetMapping(value = "/v1/stream/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamWorkflow(@RequestParam(name = "workflow_id") String id,
                              @RequestParam(name = "last_event_id", required = false) String cursor,
                              @RequestParam(defaultValue = "") String types,
                              @RequestHeader(name = "Last-Event-ID", required = false) String header, HttpSession session) {
        return streamEvents(id, cursor == null ? header : cursor, types, gatewayOwner(session));
    }

    private SseEmitter streamEvents(String id, String cursor, String types, String owner) {
        var subscription = gatewayEvents.subscribe(id, cursor, types, owner);
        var emitter = new SseEmitter(180000L);
        var worker = Thread.ofVirtual().unstarted(() -> {
            try {
                emitter.send(SseEmitter.event().comment("connected to workflow " + id));
                gatewayEvents.follow(subscription, event -> emitter.send(SseEmitter.event()
                                .id(event.get("seq").toString()).name((String) event.get("type")).data(event)),
                        () -> emitter.send(SseEmitter.event().comment("ping")));
                emitter.complete();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });
        emitter.onCompletion(worker::interrupt);
        emitter.onTimeout(worker::interrupt);
        emitter.onError(error -> worker.interrupt());
        worker.start();
        return emitter;
    }

    @GetMapping("/v1/tasks")
    Object listTasks(@RequestParam(defaultValue = "") String limit, @RequestParam(defaultValue = "") String offset,
                     @RequestParam(name = "session_id", defaultValue = "") String sessionId,
                     @RequestParam(defaultValue = "") String status, HttpSession session) {
        int pageSize = parseTaskPageParameter(limit, 20);
        if (pageSize < 1 || pageSize > 100) pageSize = 20;
        return store.listSimpleTasks(gatewayOwner(session), sessionId, status, pageSize,
                Math.max(0, parseTaskPageParameter(offset, 0)));
    }

    private static int parseTaskPageParameter(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @PostMapping("/v1/tasks/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object cancelTask(@PathVariable String id, HttpSession session) {
        String owner = gatewayOwner(session);
        store.simpleTask(id, owner);
        generation.cancel(id, owner);
        return Map.of("success", true, "message", "Task cancelled");
    }

    @PostMapping("/v1/tasks/{id}/pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object pauseTask(@PathVariable String id, HttpSession session) {
        String owner = gatewayOwner(session);
        store.simpleTask(id, owner);
        generation.pause(id, owner);
        return Map.of("success", true, "task_id", id, "message", "Task paused");
    }

    @PostMapping("/v1/tasks/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object resumeTask(@PathVariable String id, HttpSession session) {
        String owner = gatewayOwner(session);
        store.simpleTask(id, owner);
        generation.resume(id, owner);
        return Map.of("success", true, "task_id", id, "message", "Task resumed");
    }

    /**
     * 接收手动保存的文件快照与编辑时的基准版本。
     *
     * <p>示例：<code>new SaveInput(Map.of("index.html", html), baseVersion)</code>。</p>
     */
    record SaveInput(Map<String, String> files, String baseVersion) {
    }

    /**
     * 指定要恢复的历史版本和当前基准版本。
     *
     * <p>示例：<code>new RestoreInput(oldVersion, currentVersion)</code>。</p>
     */
    record RestoreInput(String versionId, String baseVersion) {
    }

    @GetMapping("/config")
    Map<String, Object> config(HttpSession s) {
        owner(s);
        var config = new LinkedHashMap<String, Object>(catalog.view());
        config.put("demo", model.isDemo());
        config.put("model", model.isDemo() ? "内置演示模板" : model.defaultModel());
        config.put("configured", model.isConfigured());
        config.put("patterns", PatternRegistry.catalog());
        config.put("templates", templates.catalog());
        config.put("skills", skills.available().stream().filter(skill -> !skill.dangerous())
                .map(Skill::summary).toList());
        config.put("learningRouting", learningRouter.enabled());
        return config;
    }

    @GetMapping("/skills")
    Object listSkills(@RequestParam(required = false) String category) {
        return Map.of("skills", skills.available().stream()
                .filter(skill -> category == null || category.equals(skill.category()))
                .map(dev.atoms.agent.skill.Skill::summary).toList());
    }

    @GetMapping("/workflow-templates")
    Object workflowTemplates() {
        return Map.of("templates", templates.catalog());
    }

    @GetMapping("/skills/{reference}")
    Object skillDetails(@PathVariable String reference) {
        return skills.resolve(reference).summary();
    }

    @GetMapping("/agent/tools")
    Object tools(HttpSession s) {
        owner(s);
        return model.toolCatalog();
    }

    @GetMapping("/assets/{id}")
    ResponseEntity<byte[]> asset(@PathVariable String id, HttpSession s) {
        var asset = store.asset(id, owner(s));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) asset.get("media_type"))).header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=atoms-image." + (((String) asset.get("media_type")).equals("image/jpeg") ? "jpg" : ((String) asset.get("media_type")).equals("image/webp") ? "webp" : "png")).body((byte[]) asset.get("image_data"));
    }

    @GetMapping("/projects")
    Object list(HttpSession s) {
        return store.projects(owner(s));
    }

    @PostMapping("/projects")
    Object create(@RequestBody ProjectInput input, HttpSession s) {
        String title = input.title() == null ? "未命名项目" : input.title().trim();
        if (title.isBlank() || title.length() > 100) throw Store.error(400, "项目名称须为1～100字");
        return store.create(owner(s), title);
    }

    @GetMapping("/projects/{id}")
    Object detail(@PathVariable String id, HttpSession s) {
        return store.detail(id, owner(s));
    }

    @GetMapping("/projects/{id}/documents/search")
    Object searchProjectDocuments(@PathVariable String id, @RequestParam(required = false) String versionId,
                                  @RequestParam String query, HttpSession session) {
        return Map.of("hits", store.searchProjectDocuments(id, owner(session), versionId, query));
    }

    @GetMapping("/projects/{id}/versions")
    Object versions(@PathVariable String id, HttpSession s) {
        store.project(id, owner(s));
        return store.versions(id);
    }

    @PostMapping("/projects/{id}/runs")
    Object run(@PathVariable String id, @RequestBody RunInput input, HttpSession s) {
        return run(id, input, s, owner(s), null, null);
    }

    private Object run(String id, RunInput input, HttpSession s, String owner, Map<String, Object> context,
                       String memoryScope) {
        if (input.prompt() == null || input.prompt().isBlank() || input.prompt().length() > 10000)
            throw Store.error(400, "需求须为1～10000字");
        String mode = catalog.mode(input.mode());
        var skill = input.skill() == null || input.skill().isBlank() ? null : skills.resolve(input.skill());
        var template = templates.route(input.template(), input.templateVersion(), input.requireTemplate());
        if (template != null && (!"build".equals(mode) || skill != null
                || input.pattern() != null && !input.pattern().isBlank() && !"reflection".equals(input.pattern())))
            throw Store.error(400, "工作流模板支持构建模式与 reflection 策略，提交时请单独选择模板");
        String requestedPattern = template == null ? input.pattern() : "reflection";
        if (template == null && "build".equals(mode) && "auto".equals(requestedPattern)) {
            requestedPattern = skill == null ? learningRouter.choose(id, owner, input.prompt(), input.baseVersion() != null) : "react";
        }
        String selectedModel = catalog.route(mode, input.model(), input.prompt(), input.baseVersion(), requestedPattern);
        String pattern = PatternRegistry.resolveName("build".equals(mode) ? requestedPattern : "react");
        if (skill != null) {
            if ("research".equals(mode)) throw Store.error(400, "研究模式暂不支持技能");
            if (skill.dangerous()) throw Store.error(403, "当前身份不能执行危险技能");
            if ("image".equals(mode)) throw Store.error(400, "图像模式不支持技能");
            if ("dag".equals(pattern) && !skill.requiresRole().isBlank())
                throw Store.error(400, "指定角色的技能需要使用单 Agent 策略");
            if (!skill.requiresTools().isEmpty()) {
                if (!"build".equals(mode) || !"react".equals(pattern))
                    throw Store.error(400, "声明所需工具的技能需要使用构建模式与 ReAct 策略");
                var registry = model.tools();
                skill.requiresTools().forEach(registry::require);
            }
        }
        String approval = approvalPolicy.requirement(owner, input.prompt(), mode, selectedModel, pattern, skill, template);
        String recalledMemory = memory.recall(memoryScope);
        synchronized (s) {
            Long start = (Long) s.getAttribute("quotaStart");
            Integer count = (Integer) s.getAttribute("quotaCount");
            long now = System.currentTimeMillis();
            if (start == null || now - start > 3600000) {
                start = now;
                count = 0;
            }
            if (count >= 20) throw Store.error(429, "每个会话每小时最多生成20次，请稍后再试");
            var run = store.queue(id, owner, input.prompt(), input.baseVersion(), mode, selectedModel, pattern, skill, template, context, approval, recalledMemory,
                    memory.captureScope(memoryScope), catalog.stageModels(mode, input.model()));
            s.setAttribute("quotaStart", start);
            s.setAttribute("quotaCount", count + 1);
            generation.submit((String) run.get("id"), id, owner, input.prompt(), input.baseVersion(), mode, selectedModel);
            return run;
        }
    }

    @PostMapping("/runs/{id}/pause")
    Object pause(@PathVariable String id, HttpSession s) {
        return generation.pause(id, owner(s));
    }

    @PostMapping("/runs/{id}/resume")
    Object resume(@PathVariable String id, HttpSession s) {
        return generation.resume(id, owner(s));
    }

    @PostMapping("/runs/{id}/cancel")
    Object cancelWorkbenchRun(@PathVariable String id, HttpSession session) {
        String owner = owner(session);
        generation.cancel(id, owner);
        return store.run(id, owner);
    }

    record PlanFeedback(int version, String feedback) {}

    @GetMapping("/runs/{id}/plan-review")
    Object planReview(@PathVariable String id, HttpSession session) {
        return store.planReview(id, owner(session));
    }

    @PostMapping("/runs/{id}/plan-review")
    Object revisePlan(@PathVariable String id, @RequestBody PlanFeedback feedback, HttpSession session) {
        return generation.revisePlan(id, owner(session), feedback.version(), feedback.feedback());
    }

    @PostMapping("/runs/{id}/approval")
    Object approve(@PathVariable String id, @RequestParam boolean approved,
                   @RequestParam(required = false) String approvalId, HttpSession s) {
        return generation.approve(id, owner(s), approved, approvalId);
    }

    @GetMapping("/runs/{id}")
    Object status(@PathVariable String id, HttpSession s) {
        return store.run(id, owner(s));
    }

    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id, HttpSession s) {
        String owner = owner(s);
        store.run(id, owner);
        var emitter = new SseEmitter(180000L);
        var done = new AtomicBoolean(false);
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> {
            done.set(true);
            emitter.complete();
        });
        emitter.onError(e -> done.set(true));
        final java.util.concurrent.ScheduledFuture<?>[] future = new java.util.concurrent.ScheduledFuture<?>[1];
        future[0] = scheduler.scheduleAtFixedRate(() -> {
            if (done.get()) {
                if (future[0] != null) future[0].cancel(false);
                return;
            }
            try {
                var run = store.run(id, owner);
                emitter.send(SseEmitter.event().name("status").data(run));
                if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "PAUSED", "AWAITING_APPROVAL").contains(run.get("status"))) {
                    done.set(true);
                    emitter.complete();
                }
            } catch (Exception e) {
                done.set(true);
                emitter.completeWithError(e);
            }
        }, Duration.ofSeconds(1));
        return emitter;
    }

    @PostMapping("/projects/{id}/versions")
    Object save(@PathVariable String id, @RequestBody SaveInput input, HttpSession s) {
        SourceFiles.validate(input.files());
        return store.save(id, owner(s), input.baseVersion(), input.files(), "手动编辑保存", null);
    }

    @PostMapping("/projects/{id}/restore")
    Object restore(@PathVariable String id, @RequestBody RestoreInput input, HttpSession s) {
        String owner = owner(s);
        store.project(id, owner);
        var target = store.versions(id).stream().filter(v -> v.get("id").equals(input.versionId())).findFirst().orElseThrow(() -> Store.error(404, "版本不存在"));
        return store.save(id, owner, input.baseVersion(), store.versionFiles((String) target.get("id")), "恢复自版本 v" + target.get("number"), null);
    }

    @GetMapping("/projects/{id}/export")
    ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(required = false) String versionId, HttpSession s) throws Exception {
        var project = store.project(id, owner(s));
        String vid = versionId == null ? (String) project.get("current_version") : versionId;
        if (vid == null) throw Store.error(400, "暂无可导出的版本");
        store.requireVersion(id, vid);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=atoms-project.zip").contentType(MediaType.APPLICATION_OCTET_STREAM).body(SourceFiles.zip(store.versionFiles(vid)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", Objects.requireNonNullElse(e.getReason(), "请求失败")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", Objects.requireNonNullElse(e.getMessage(), "参数错误")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of("error", "服务暂不可用，请检查后端日志"));
    }
}
