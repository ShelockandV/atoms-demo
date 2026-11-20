package dev.atoms.web;

import dev.atoms.agent.GenerationService;
import dev.atoms.agent.model.ModelCatalog;
import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.pattern.PatternRegistry;
import dev.atoms.agent.pattern.LearningRouter;
import dev.atoms.agent.persistence.Store;
import dev.atoms.agent.skill.SkillCatalog;
import dev.atoms.agent.pattern.WorkflowTemplates;
import dev.atoms.workspace.SourceFiles;
import com.fasterxml.jackson.annotation.JsonAlias;

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
    final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    ApiController(Store store, GenerationService generation, ModelClient model, ModelCatalog catalog, SkillCatalog skills,
                  WorkflowTemplates templates, LearningRouter learningRouter) {
        this.catalog = catalog;
        this.store = store;
        this.generation = generation;
        this.model = model;
        this.skills = skills;
        this.templates = templates;
        this.learningRouter = learningRouter;
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
    record TaskInput(String query, @com.fasterxml.jackson.annotation.JsonProperty("session_id") String sessionId,
                     Map<String, Object> context, String mode, String skill,
                     @com.fasterxml.jackson.annotation.JsonProperty("model_override") String modelOverride,
                     @JsonAlias("template_name") String template, @JsonAlias("template_version") String templateVersion,
                     @JsonAlias("disable_ai") boolean requireTemplate) {}

    private String gatewayOwner(HttpSession session) {
        var request = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        Object tenant = request.getAttribute("gatewayOwner", org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return tenant instanceof String key ? key : owner(session);
    }

    @PostMapping("/v1/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    Object submitTask(@RequestBody TaskInput input, HttpSession session) {
        if (input.query() == null || input.query().isBlank() || input.query().length() > 10000)
            throw Store.error(400, "query 须为1～10000字");
        var context = input.context() == null ? Map.<String, Object>of() : input.context();
        if (store.encode(context).length() > 16000) throw Store.error(400, "context 超过长度限制");
        String name = input.template();
        if (name == null || name.isBlank()) name = context.get("template") instanceof String value
                ? value : context.get("template_name") instanceof String alias ? alias : null;
        String version = input.templateVersion();
        if (version == null && context.get("template_version") instanceof String value) version = value;
        boolean required = input.requireTemplate() || Boolean.TRUE.equals(context.get("disable_ai"));
        var template = templates.route(name, version, required);
        String mode = input.mode() == null || input.mode().isBlank() ? "simple" : input.mode();
        if (template == null && !Set.of("simple", "standard", "complex", "dag", "auto").contains(mode))
            throw Store.error(400, "当前任务入口支持 simple、standard、complex、dag 和 auto");
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
        var queued = (Map<String, Object>) run(id, new RunInput(input.query(), (String) project.get("currentVersion"),
                pattern == null ? "chat" : "build", input.modelOverride(), pattern, input.skill(),
                template == null ? null : name, template == null ? null : version, required), session, owner, context);
        return Map.of("task_id", queued.get("id"), "session_id", id,
                "status", "TASK_STATUS_QUEUED", "created_at", queued.get("createdAt"));
    }

    @GetMapping("/v1/tasks/{id}")
    Object taskStatus(@PathVariable String id, HttpSession session) {
        return store.simpleTask(id, gatewayOwner(session));
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
        store.simpleTask(id, owner);
        long start;
        try {
            start = cursor == null || cursor.isBlank() ? 0 : Long.parseLong(cursor);
            if (start < 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            throw Store.error(400, "无效的事件游标");
        }
        Set<String> filter = new HashSet<>(Arrays.asList(types.split(",")));
        var emitter = new SseEmitter(180000L);
        var worker = Thread.ofVirtual().unstarted(() -> {
            long last = start;
            try {
                emitter.send(SseEmitter.event().comment("connected to workflow " + id));
                while (!Thread.currentThread().isInterrupted()) {
                    var status = store.simpleTask(id, owner).get("status");
                    var events = store.taskEventsAfter(id, last);
                    for (var event : events) {
                        last = ((Number) event.get("seq")).longValue();
                        String type = (String) event.get("type");
                        if (types.isBlank() || filter.contains(type))
                            emitter.send(SseEmitter.event().id(Long.toString(last)).name(type).data(event));
                    }
                    if (events.size() == 200) continue;
                    if (Set.of("TASK_STATUS_COMPLETED", "TASK_STATUS_FAILED", "TASK_STATUS_CANCELLED").contains(status)) break;
                    Thread.sleep(500);
                }
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

    @GetMapping("/projects/{id}/versions")
    Object versions(@PathVariable String id, HttpSession s) {
        store.project(id, owner(s));
        return store.versions(id);
    }

    @PostMapping("/projects/{id}/runs")
    Object run(@PathVariable String id, @RequestBody RunInput input, HttpSession s) {
        return run(id, input, s, owner(s), null);
    }

    private Object run(String id, RunInput input, HttpSession s, String owner, Map<String, Object> context) {
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
        synchronized (s) {
            Long start = (Long) s.getAttribute("quotaStart");
            Integer count = (Integer) s.getAttribute("quotaCount");
            long now = System.currentTimeMillis();
            if (start == null || now - start > 3600000) {
                start = now;
                count = 0;
            }
            if (count >= 20) throw Store.error(429, "每个会话每小时最多生成20次，请稍后再试");
            var run = store.queue(id, owner, input.prompt(), input.baseVersion(), mode, selectedModel, pattern, skill, template, context);
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

    @PostMapping("/runs/{id}/approval")
    Object approve(@PathVariable String id, @RequestParam boolean approved, HttpSession s) {
        return generation.approve(id, owner(s), approved);
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
