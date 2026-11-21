package dev.atoms.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.atoms.agent.approval.TaskApprovalPolicy;
import dev.atoms.agent.runtime.GenerationService;
import dev.atoms.agent.model.ModelCatalog;
import dev.atoms.agent.persistence.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Text-only Chat Completions adapter over the same durable task execution path. */
@RestController
@RequestMapping("/v1")
public class OpenAiController {
    private static final Duration WAIT_LIMIT = Duration.ofMinutes(3);
    private static final String CHAT_ALIAS = "shannon-chat";
    private static final String COMPLEX_ALIAS = "shannon-complex";
    private static final dev.atoms.agent.pattern.WorkflowTemplate COMPLEX_TEMPLATE =
            new dev.atoms.agent.pattern.WorkflowTemplate(COMPLEX_ALIAS, "", "1.0.0",
                    List.of(new dev.atoms.agent.pattern.WorkflowTemplate.Node(
                            "answer", "supervisor", List.of(), null, null, null, null, List.of())), List.of(), null);
    private static final Map<String, String> RESEARCH_ALIASES = Map.of(
            "shannon-quick-research", "quick", "shannon-standard-research", "standard", "shannon-deep-research", "deep");
    private final Store store;
    private final GenerationService generation;
    private final ModelCatalog models;
    private final dev.atoms.workspace.GatewayMemoryFiles memory;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final TaskApprovalPolicy approvalPolicy;

    OpenAiController(Store store, GenerationService generation, ModelCatalog models, com.fasterxml.jackson.databind.ObjectMapper json,
                     TaskApprovalPolicy approvalPolicy, dev.atoms.workspace.GatewayMemoryFiles memory) {
        this.store = store;
        this.generation = generation;
        this.models = models;
        this.json = json;
        this.approvalPolicy = approvalPolicy;
        this.memory = memory;
    }

    @GetMapping("/models")
    Object listModels() {
        var ids = new java.util.LinkedHashSet<>(models.chatModels());
        ids.add(CHAT_ALIAS);
        ids.add(COMPLEX_ALIAS);
        RESEARCH_ALIASES.keySet().stream().sorted().forEach(ids::add);
        return Map.of("object", "list", "data", ids.stream().map(OpenAiController::modelView).toList());
    }

    @GetMapping("/llm-models")
    Object providerModels(@RequestParam(defaultValue = "") String tier) {
        return Map.of("models", models.providerModels(tier));
    }

    @GetMapping("/models/{*id}")
    Object model(@PathVariable String id) {
        String name = id.startsWith("/") ? id.substring(1) : id;
        requireModel(name);
        return modelView(name);
    }

    private static Map<String, Object> modelView(String id) {
        return Map.of("id", id, "object", "model", "created", 0, "owned_by", "atoms");
    }

    private void requireModel(String name) {
        if (!workflowAlias(name) && !models.chatModels().contains(name)) throw Store.error(404, "Model not found");
    }

    private static boolean workflowAlias(String model) {
        return CHAT_ALIAS.equals(model) || COMPLEX_ALIAS.equals(model) || RESEARCH_ALIASES.containsKey(model);
    }

    @PostMapping("/chat/completions")
    Object complete(@RequestBody JsonNode body, HttpServletRequest request, HttpSession session) {
        var input = parse(body, true);
        String model = input.model().isEmpty() ? CHAT_ALIAS : input.model();
        requireModel(model);
        var options = new LinkedHashMap<>(chatToolOptions(body));
        String owner = request.getAttribute("gatewayOwner") instanceof String tenant
                ? tenant : session.getAttribute("owner").toString();
        addCacheKey(options, body.get("prompt_cache_key"), owner);
        var run = submit(owner, session, input, model, options, false, GatewayMemoryController.taskScope(request));
        var pending = awaitingApproval(run, owner);
        if (pending != null) return pending;
        Instant created = Instant.now();
        if (input.stream()) {
            var emitter = new SseEmitter(WAIT_LIMIT.toMillis() + 1000);
            var worker = Thread.ofVirtual().unstarted(() -> stream(owner, run, model, created, emitter, input.includeUsage(), input.candidates()));
            emitter.onCompletion(worker::interrupt);
            emitter.onTimeout(worker::interrupt);
            worker.start();
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
        }
        return jsonResponse(owner, run, outcome -> {
            var choices = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < input.candidates(); index++) {
                var saved = completionMessage(run, index);
                String text = saved.path("content").asText(index == 0 ? outcome.content() : "");
                var calls = saved.path("tool_calls");
                boolean toolCalls = calls.isArray() && !calls.isEmpty();
                var message = new LinkedHashMap<String, Object>();
                message.put("role", "assistant");
                message.put("content", text.isEmpty() && toolCalls ? null : text);
                if (toolCalls) message.put("tool_calls", calls);
                copyProviderFields(saved, message);
                choices.add(Map.of("index", index, "message", message, "finish_reason",
                        saved.path("finish_reason").asText(toolCalls ? "tool_calls" : "stop")));
            }
            var data = wire(run, "chat.completion", reportedModel(model, outcome.usage()), created, choices);
            data.put("usage", usageView(outcome.usage()));
            return data;
        });
    }

    private static void addCacheKey(Map<String, Object> options, JsonNode key, String owner) {
        if (key == null || key.isNull()) return;
        if (!key.isTextual() || key.asText().isBlank() || key.asText().length() > 128)
            throw Store.error(400, "Invalid prompt cache key");
        options.put("prompt_cache_key", GatewayKeys.hash(owner + "\n" + key.asText()).substring(0, 48));
    }

    private static void copyProviderFields(JsonNode source, Map<String, Object> target) {
        for (String name : List.of("provider_content", "reasoning_content"))
            if (source.hasNonNull(name)) target.put(name, source.get(name));
    }

    private static Map<String, Object> chatToolOptions(JsonNode body) {
        if (!body.has("tools") && !body.has("tool_choice")) return Map.of();
        var options = new LinkedHashMap<String, Object>();
        if (body.has("tools")) options.put("tools", legacyTools(body.path("tools")));
        if (body.has("tool_choice")) {
            var choice = body.path("tool_choice");
            if (!choice.isTextual() || !Set.of("auto", "none", "required").contains(choice.asText()))
                throw Store.error(400, "tool_choice must be auto, none or required");
            if (!body.path("tools").isArray() || body.path("tools").isEmpty())
                throw Store.error(400, "tool_choice requires non-empty tools");
            options.put("tool_choice", choice.asText());
        }
        return options;
    }

    @PostMapping("/completions")
    Object legacyCompletion(@RequestBody JsonNode body, HttpServletRequest request, HttpSession session) {
        if (!body.isObject()) throw Store.error(400, "A JSON object is required");
        var fields = body.fieldNames();
        var accepted = Set.of("messages", "model_tier", "specific_model", "temperature", "max_tokens", "tools",
                "stream", "cache_key", "session_id", "cache_source");
        while (fields.hasNext()) {
            String field = fields.next();
            if (!accepted.contains(field)) throw Store.error(400, "Unsupported parameter: " + field);
        }
        var options = new LinkedHashMap<String, Object>();
        if (body.hasNonNull("temperature")) {
            var temperature = body.path("temperature");
            if (!temperature.isNumber() || temperature.asDouble() < 0 || temperature.asDouble() > 2)
                throw Store.error(400, "temperature must be a number from 0 to 2");
            options.put("temperature", temperature.asDouble());
        }
        if (body.hasNonNull("tools")) options.put("tools", legacyTools(body.path("tools")));
        var normalized = json.createObjectNode();
        normalized.set("messages", body.path("messages"));
        if (body.hasNonNull("max_tokens")) normalized.set("max_tokens", body.path("max_tokens"));
        if (body.hasNonNull("stream")) normalized.set("stream", body.path("stream"));
        var input = parse(normalized, true);
        if (!input.images().isEmpty()) throw Store.error(400, "Use chat/completions for image input");
        String tier = body.path("model_tier").asText("small").toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("small", "medium", "large").contains(tier)) throw Store.error(400, "Invalid model_tier");
        String model = body.path("specific_model").asText("");
        if (model.isBlank()) model = models.completionModel(tier, input.query());
        if (!models.chatModels().contains(model)) throw Store.error(404, "Model not found");
        String owner = request.getAttribute("gatewayOwner") instanceof String tenant
                ? tenant : session.getAttribute("owner").toString();
        addCacheKey(options, body.get("cache_key"), owner);
        String run = submit(owner, session, input, model, options, true, null);
        var pending = awaitingApproval(run, owner);
        if (pending != null) return pending;
        String requested = model;
        return input.stream() ? legacyStream(owner, run, model)
                : jsonResponse(owner, run, outcome -> legacyResult(run, requested, outcome));
    }

    private static List<JsonNode> legacyTools(JsonNode tools) {
        if (!tools.isArray() || tools.size() > 32) throw Store.error(400, "tools must be an array of at most 32 functions");
        var definitions = new ArrayList<JsonNode>();
        for (var tool : tools) {
            var function = tool.has("function") ? tool.path("function") : tool;
            if ((tool.has("type") && !"function".equals(tool.path("type").asText()))
                    || !function.path("name").asText().matches("[A-Za-z0-9_-]{1,64}")
                    || !function.path("parameters").isObject())
                throw Store.error(400, "Invalid function definition");
            var definition = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "function");
            definition.set("function", function);
            definitions.add(definition);
        }
        return definitions;
    }

    @FunctionalInterface
    private interface CompletionResult {
        Map<String, Object> build(Outcome outcome) throws Exception;
    }

    private DeferredResult<ResponseEntity<Object>> jsonResponse(String owner, String run, CompletionResult result) {
        var response = new DeferredResult<ResponseEntity<Object>>(WAIT_LIMIT.toMillis() + 1000);
        var worker = Thread.ofVirtual().unstarted(() -> {
            try {
                response.setResult(ResponseEntity.ok(result.build(await(run, owner))));
            } catch (TimeoutException expired) {
                response.setResult(ResponseEntity.status(504).body(error("Task exceeded the response timeout", "timeout", "task_timeout")));
            } catch (Exception failure) {
                if (!Thread.currentThread().isInterrupted())
                    response.setResult(ResponseEntity.status(502).body(error("Task did not complete", "api_error", "task_failed")));
            }
        });
        response.onCompletion(worker::interrupt);
        response.onTimeout(worker::interrupt);
        worker.start();
        return response;
    }

    private ResponseEntity<SseEmitter> legacyStream(String owner, String run, String model) {
        var emitter = new SseEmitter(WAIT_LIMIT.toMillis() + 1000);
        var worker = Thread.ofVirtual().unstarted(() -> {
            try {
                emitter.send(SseEmitter.event().comment("queued"));
                long[] cursor = {0};
                String[] attempt = {null};
                var sent = new StringBuilder();
                var outcome = await(run, owner, ping -> {
                    while (true) {
                        var deltas = store.completionDeltasAfter(run, cursor[0]);
                        for (var delta : deltas) {
                            if (attempt[0] != null && !attempt[0].equals(delta.token()))
                                throw new java.io.IOException("Model stream restarted");
                            attempt[0] = delta.token();
                            emitter.send(SseEmitter.event().data(Map.of("type", "content_delta", "text", delta.content())));
                            sent.append(delta.content());
                            cursor[0] = delta.seq();
                        }
                        if (deltas.size() < 200) break;
                    }
                    if (ping) emitter.send(SseEmitter.event().comment("waiting"));
                });
                if (!outcome.content().startsWith(sent.toString()))
                    throw new java.io.IOException("Model stream differs from the saved completion");
                String remaining = outcome.content().substring(sent.length());
                if (!remaining.isEmpty())
                    emitter.send(SseEmitter.event().data(Map.of("type", "content_delta", "text", remaining)));
                var done = legacyResult(run, model, outcome);
                done.put("type", "done");
                emitter.send(SseEmitter.event().data(done));
                emitter.send(SseEmitter.event().data("[DONE]"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                try {
                    emitter.send(SseEmitter.event().data(Map.of("type", "error", "message", "Task did not complete")));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                } catch (java.io.IOException | IllegalStateException disconnected) {
                    // The client closed the connection.
                }
            } finally {
                emitter.complete();
            }
        });
        emitter.onCompletion(worker::interrupt);
        emitter.onTimeout(worker::interrupt);
        worker.start();
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private Map<String, Object> legacyResult(String run, String model, Outcome outcome) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("model", reportedModel(model, outcome.usage()));
        result.put("provider", "atoms");
        result.put("output_text", outcome.content());
        var tokens = usageView(outcome.usage());
        result.put("usage", Map.of("input_tokens", tokens.get("prompt_tokens"),
                "output_tokens", tokens.get("completion_tokens"), "total_tokens", tokens.get("total_tokens"),
                "cost_usd", store.modelCost(run)));
        var message = completionMessage(run);
        copyProviderFields(message, result);
        if (message.path("finish_reason").isTextual()) result.put("finish_reason", message.path("finish_reason").asText());
        var functions = new ArrayList<Map<String, Object>>();
        for (var call : message.path("tool_calls")) {
            var function = call.path("function");
            if (function.path("name").isTextual() && !function.path("arguments").isMissingNode()) {
                var entry = new LinkedHashMap<String, Object>();
                if (call.path("id").isTextual()) entry.put("id", call.path("id").asText());
                for (String field : List.of("provider_call_id", "thought_signature"))
                    if (call.path(field).isTextual()) entry.put(field, call.path(field).asText());
                entry.put("name", function.path("name").asText());
                entry.put("arguments", function.path("arguments"));
                functions.add(entry);
            }
        }
        if (!functions.isEmpty()) {
            result.put("function_call", functions.getFirst());
            result.put("tool_calls", functions);
        } else if (message.path("function_call").isObject()) {
            result.put("function_call", message.path("function_call"));
        }
        result.putIfAbsent("finish_reason", result.containsKey("tool_calls") ? "tool_calls" : result.containsKey("function_call") ? "function_call" : "stop");
        return result;
    }

    private JsonNode completionMessage(String run, int index) throws com.fasterxml.jackson.core.JsonProcessingException {
        for (var node : store.dagNodes(run)) {
            if ((index == 0 ? "completion.message" : "completion.message." + index).equals(node.get("id")) && "SUCCEEDED".equals(node.get("status")))
                return json.readTree((String) node.get("output"));
        }
        return json.missingNode();
    }

    private JsonNode completionMessage(String run) throws com.fasterxml.jackson.core.JsonProcessingException {
        return completionMessage(run, 0);
    }

    private ResponseEntity<?> awaitingApproval(String run, String owner) {
        var task = store.simpleTask(run, owner);
        if (!Boolean.TRUE.equals(task.get("awaiting_approval"))) return null;
        var body = new LinkedHashMap<>(error("Task requires approval", "invalid_request_error", "approval_required"));
        body.put("task_id", run);
        body.put("approval_id", run);
        return ResponseEntity.status(409).body(body);
    }

    private String submit(String owner, HttpSession session, Inbound input, String model, Map<String, Object> options, boolean legacy, String memoryScope) {
        synchronized (session) {
            long now = System.currentTimeMillis();
            Long start = (Long) session.getAttribute("quotaStart");
            Integer count = (Integer) session.getAttribute("quotaCount");
            if (start == null || now - start > 3_600_000) {
                start = now;
                count = 0;
            }
            if (count >= 20) throw Store.error(429, "Session request quota exceeded");
            String strategy = RESEARCH_ALIASES.get(model);
            boolean complex = COMPLEX_ALIAS.equals(model);
            String mode = legacy ? "openai-completion"
                    : complex ? "openai-complex" : strategy == null ? "openai-chat" : "research";
            String backendModel = workflowAlias(model) ? models.select("chat", null) : model;
            var template = complex ? COMPLEX_TEMPLATE : null;
            String approval = approvalPolicy.requirement(owner, input.query(), mode, backendModel, "react", null, template);
            var project = store.createGatewaySession(owner);
            String pid = (String) project.get("id");
            var context = new LinkedHashMap<String, Object>();
            context.put("messages", input.messages());
            context.put("max_tokens", input.maxTokens());
            if (!input.images().isEmpty()) context.put("images", input.images());
            if (input.candidates() > 1) context.put("completion_candidates", input.candidates());
            if (legacy || "openai-chat".equals(mode) || !options.isEmpty() || input.candidates() > 1) {
                context.put("completion_options", options);
                context.put("completion_stream", legacy && input.stream());
            }
            if ((strategy != null || complex) && (input.candidates() > 1 || !options.isEmpty() || input.messages().stream()
                    .anyMatch(message -> "tool".equals(message.get("role"))))) context.put("workflow_model_completion", true);
            if (strategy != null) {
                context.put("openai_model", model);
                context.put("research_strategy", strategy);
            }
            String base = strategy == null || input.attachments().isEmpty() ? null
                    : (String) store.save(pid, owner, null, input.attachments(), "研究请求附件", null).get("id");
            String id = (String) store.queue(pid, owner, input.query(), base, mode, backendModel, "react",
                    null, template, context, approval, memory.recall(memoryScope), memory.captureScope(memoryScope)).get("id");
            session.setAttribute("quotaStart", start);
            session.setAttribute("quotaCount", count + 1);
            generation.submit(id, pid, owner, input.query(), base, mode, backendModel);
            return id;
        }
    }

    private void stream(String owner, String run, String model, Instant created, SseEmitter emitter, boolean includeUsage, int candidates) {
        try {
            sendChunk(emitter, run, model, created, Map.of("role", "assistant"), null);
            long[] cursor = {0};
            var answer = await(run, owner, keepalive -> {
                var events = store.taskEventsAfter(run, cursor[0]);
                for (var event : events) {
                    var chunk = wire(run, "chat.completion.chunk", model, created,
                            List.of(chunkChoice(Map.of(), null)));
                    String type = (String) event.get("type");
                    long timestamp = Instant.parse(event.get("timestamp").toString()).getEpochSecond();
                    chunk.put("shannon_events", List.of(Map.of(
                            "type", "WORKFLOW_FAILED".equals(type) ? "ERROR_OCCURRED" : type,
                            "timestamp", timestamp)));
                    emitter.send(SseEmitter.event().data(chunk));
                    cursor[0] = ((Number) event.get("seq")).longValue();
                }
                if (keepalive && events.isEmpty()) emitter.send(SseEmitter.event().comment("waiting"));
            });
            for (int index = 0; index < candidates; index++) {
                var saved = completionMessage(run, index);
                String text = saved.path("content").asText(index == 0 ? answer.content() : "");
                var calls = saved.path("tool_calls");
                boolean toolCalls = calls.isArray() && !calls.isEmpty();
                if (index > 0) sendChunk(emitter, run, model, created, index, Map.of("role", "assistant"), null);
                var providerFields = new LinkedHashMap<String, Object>();
                copyProviderFields(saved, providerFields);
                if (!providerFields.isEmpty()) sendChunk(emitter, run, model, created, index, providerFields, null);
                if (!text.isEmpty() || !toolCalls)
                    sendChunk(emitter, run, model, created, index, Map.of("content", text), null);
                if (toolCalls) {
                    var delta = json.createArrayNode();
                    for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                        var call = delta.addObject().put("index", callIndex);
                        calls.get(callIndex).fields().forEachRemaining(field -> call.set(field.getKey(), field.getValue()));
                    }
                    sendChunk(emitter, run, model, created, index, Map.of("tool_calls", delta), null);
                }
                sendChunk(emitter, run, model, created, index, Map.of(),
                        saved.path("finish_reason").asText(toolCalls ? "tool_calls" : "stop"));
            }
            if (includeUsage) {
                var last = wire(run, "chat.completion.chunk", model, created, List.of());
                last.put("usage", usageView(answer.usage()));
                emitter.send(SseEmitter.event().data(last));
            }
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (InterruptedException disconnected) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            try {
                emitter.send(SseEmitter.event().data(error("Task did not complete", "api_error", "task_failed")));
                emitter.send(SseEmitter.event().data("[DONE]"));
            } catch (Exception disconnected) {
                // The client may have closed the stream while the task was executing.
            }
            emitter.complete();
        }
    }

    private static void sendChunk(SseEmitter emitter, String run, String model, Instant created,
                                  Map<String, ?> delta, String finish) throws java.io.IOException {
        sendChunk(emitter, run, model, created, 0, delta, finish);
    }

    private static void sendChunk(SseEmitter emitter, String run, String model, Instant created, int index,
                                  Map<String, ?> delta, String finish) throws java.io.IOException {
        emitter.send(SseEmitter.event().data(wire(run, "chat.completion.chunk", model, created, List.of(chunkChoice(index, delta, finish)))));
    }

    private static Map<String, Object> chunkChoice(Map<String, ?> delta, String finish) {
        return chunkChoice(0, delta, finish);
    }

    private static Map<String, Object> chunkChoice(int index, Map<String, ?> delta, String finish) {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", index);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        return choice;
    }

    @FunctionalInterface
    private interface StreamProgress {
        void send(boolean keepalive) throws java.io.IOException;
    }

    private Outcome await(String run, String owner) throws Exception {
        return await(run, owner, null);
    }

    private Outcome await(String run, String owner, StreamProgress progress) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        long nextPing = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        while (System.nanoTime() < deadline) {
            var status = store.simpleTask(run, owner);
            if (progress != null) {
                boolean ping = System.nanoTime() > nextPing;
                progress.send(ping);
                if (ping) nextPing = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            }
            if ("TASK_STATUS_COMPLETED".equals(status.get("status"))) {
                Object value = store.run(run, owner).get("usage");
                var usage = value instanceof Map<?, ?> entries ? entries : Map.of();
                return new Outcome((String) status.getOrDefault("result", ""), usage);
            }
            if (Set.of("TASK_STATUS_FAILED", "TASK_STATUS_CANCELLED", "TASK_STATUS_TIMEOUT").contains(status.get("status")))
                throw new IllegalStateException("Task failed");
            Thread.sleep(250);
        }
        throw new TimeoutException("Task not completed");
    }

    private record Outcome(String content, Map<?, ?> usage) {}

    private static String reportedModel(String requested, Map<?, ?> usage) {
        if (workflowAlias(requested)) return requested;
        if (usage.get("models") instanceof List<?> entries && entries.size() == 1
                && entries.getFirst() instanceof Map<?, ?> entry && entry.get("model") instanceof String actual)
            return actual;
        return requested;
    }

    private static Map<String, Long> usageView(Map<?, ?> usage) {
        long prompt = usage.get("promptTokens") instanceof Number n ? n.longValue() : 0;
        long completion = usage.get("completionTokens") instanceof Number n ? n.longValue() : 0;
        return Map.of("prompt_tokens", prompt, "completion_tokens", completion, "total_tokens", prompt + completion);
    }

    private static Map<String, Object> wire(String id, String type, String model, Instant created, List<?> choices) {
        return new LinkedHashMap<>(Map.of("id", "chatcmpl-" + id, "object", type, "created", created.getEpochSecond(),
                "model", model, "choices", choices));
    }

    private record Inbound(String model, List<Map<String, String>> messages, String query,
                           Map<String, String> attachments, Map<Integer, List<String>> images, int maxTokens, boolean stream, boolean includeUsage, int candidates) {}

    private static Inbound parse(JsonNode body, boolean allowToolHistory) {
        if (!body.isObject())
            throw Store.error(400, "A JSON object is required");
        if (body.has("model") && (!body.path("model").isTextual() || body.path("model").asText().isBlank()))
            throw Store.error(400, "model must be a non-empty string");
        var supported = Set.of("model", "messages", "stream", "stream_options", "max_completion_tokens", "max_tokens", "n", "tools", "tool_choice", "prompt_cache_key");
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!supported.contains(field)) throw Store.error(400, "Unsupported parameter: " + field);
        }
        if (body.has("n") && (!body.path("n").isIntegralNumber() || !body.path("n").canConvertToInt()
                || body.path("n").asInt() < 1 || body.path("n").asInt() > 4))
            throw Store.error(400, "n must be an integer from 1 to 4");
        if (body.has("stream") && !body.path("stream").isBoolean())
            throw Store.error(400, "stream must be a boolean");
        var streamOptions = body.path("stream_options");
        if (!streamOptions.isMissingNode() && (!streamOptions.isObject()
                || (streamOptions.has("include_usage") && !streamOptions.path("include_usage").isBoolean())))
            throw Store.error(400, "stream_options.include_usage must be a boolean");
        var messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty() || messages.size() > 100)
            throw Store.error(400, "messages must contain 1 to 100 text messages");
        var normalized = new ArrayList<Map<String, String>>();
        var attachments = new LinkedHashMap<String, String>();
        String requested = body.path("model").asText();
        boolean research = RESEARCH_ALIASES.containsKey(requested);
        var images = new LinkedHashMap<Integer, List<String>>();
        int imageCount = 0;
        var pendingCalls = new LinkedHashMap<String, String>();
        String query = "";
        int size = 0;
        for (var message : messages) {
            String role = message.path("role").asText();
            if (!allowToolHistory && (message.hasNonNull("tool_calls") || message.hasNonNull("function_call")))
                throw Store.error(400, "Tool history requires a direct chat model");
            boolean toolResult = allowToolHistory && Set.of("tool", "function").contains(role);
            boolean hasCalls = allowToolHistory && "assistant".equals(role)
                    && (message.hasNonNull("tool_calls") || message.hasNonNull("function_call"));
            if (!toolResult && !Set.of("system", "developer", "user", "assistant").contains(role))
                throw Store.error(400, "Unsupported chat role");
            if (!toolResult && !pendingCalls.isEmpty())
                throw Store.error(400, "Tool results must immediately follow their assistant calls");
            var wireMessage = new LinkedHashMap<String, String>();
            wireMessage.put("role", toolResult ? "tool" : "developer".equals(role) ? "system" : role);
            for (String field : List.of("provider_content", "reasoning_content")) {
                if (!"assistant".equals(role) || !message.hasNonNull(field)) continue;
                var value = message.get(field);
                if (("provider_content".equals(field) ? !value.isObject() : !value.isTextual())
                        || value.toString().length() > 32_000)
                    throw Store.error(400, "Invalid " + field);
                String serialized = value.isTextual() ? value.asText() : value.toString();
                wireMessage.put(field, serialized);
                size += serialized.length();
            }
            if (hasCalls) {
                var calls = historyCalls(message, normalized.size(), pendingCalls);
                wireMessage.put("tool_calls", calls.toString());
                size += calls.toString().length();
            }
            if (toolResult) {
                String callId = "function".equals(role) && pendingCalls.size() == 1
                        && pendingCalls.containsValue(message.path("name").asText())
                        ? pendingCalls.keySet().iterator().next() : message.path("tool_call_id").asText();
                if (pendingCalls.remove(callId) == null) throw Store.error(400, "Unknown or duplicate tool_call_id");
                wireMessage.put("tool_call_id", callId);
            }
            var content = hasCalls && !message.hasNonNull("content") ? new TextContent("", List.of(), List.of())
                    : textContent(message.path("content"), "user".equals(role), attachments);
            if (!content.images().isEmpty()) {
                imageCount += content.images().size();
                if (research || COMPLEX_ALIAS.equals(requested) || imageCount > 4)
                    throw Store.error(400, "At most four images are supported in direct chat");
                images.put(normalized.size(), content.images());
            }
            String text = content.text();
            if (!research && !content.attachments().isEmpty()) {
                var prompt = new StringBuilder(text);
                for (String file : content.attachments())
                    prompt.append("\n\n[Attachment: ").append(file).append("]\n").append(attachments.get(file));
                text = prompt.toString();
            }
            size += text.length();
            if (size > 64000) throw Store.error(400, "messages too long");
            if ("user".equals(role)) query = content.text();
            wireMessage.put("content", text);
            normalized.add(wireMessage);
        }
        if (!pendingCalls.isEmpty()) throw Store.error(400, "A tool result is required for every assistant call");
        if (query.isBlank()) throw Store.error(400, "A user message is required");
        if (query.length() > 10000) query = query.substring(0, query.offsetByCodePoints(0, Math.min(5000, query.codePointCount(0, query.length()))));
        JsonNode limit = body.has("max_completion_tokens") ? body.path("max_completion_tokens") : body.path("max_tokens");
        if (!limit.isMissingNode() && (!limit.isIntegralNumber() || !limit.canConvertToInt()
                || limit.asInt() < 1 || limit.asInt() > 65536))
            throw Store.error(400, "Invalid completion token limit");
        return new Inbound(body.path("model").asText(), normalized, query, attachments, images, limit.asInt(0), body.path("stream").asBoolean(),
                streamOptions.path("include_usage").asBoolean(), body.path("n").asInt(1));
    }

    private static JsonNode historyCalls(JsonNode message, int index, Map<String, String> pending) {
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (message.hasNonNull("tool_calls")) {
            var calls = message.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty() || calls.size() > 32)
                throw Store.error(400, "Assistant tool_calls must contain 1 to 32 calls");
            for (var call : calls) {
                if (call.has("type") && !"function".equals(call.path("type").asText()))
                    throw Store.error(400, "Only function tool calls are supported");
                var normalized = appendHistoryCall(result, call.path("id").asText(),
                        call.has("function") ? call.path("function") : call, pending);
                for (String field : List.of("provider_call_id", "thought_signature"))
                    if (call.path(field).isTextual()) normalized.put(field, call.path(field).asText());
            }
        } else {
            appendHistoryCall(result, "legacy-call-" + index, message.path("function_call"), pending);
        }
        return result;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode appendHistoryCall(com.fasterxml.jackson.databind.node.ArrayNode result, String id,
                                          JsonNode function, Map<String, String> pending) {
        String name = function.path("name").asText();
        var arguments = function.path("arguments");
        if (!id.matches("[A-Za-z0-9_.:-]{1,128}") || !name.matches("[A-Za-z0-9_-]{1,64}")
                || (!arguments.isTextual() && !arguments.isObject()) || pending.putIfAbsent(id, name) != null)
            throw Store.error(400, "Invalid or duplicate historical function call");
        var call = result.addObject().put("id", id).put("type", "function");
        call.putObject("function").put("name", name)
                .put("arguments", arguments.isTextual() ? arguments.asText() : arguments.toString());
        return call;
    }

    private record TextContent(String text, List<String> attachments, List<String> images) {}

    private static TextContent textContent(JsonNode content, boolean allowFiles, Map<String, String> attachments) {
        if (content.isTextual()) return new TextContent(content.asText(), List.of(), List.of());
        if (!content.isArray() || content.isEmpty()) throw Store.error(400, "Text message content is required");
        var result = new java.util.StringJoiner("\n");
        var files = new ArrayList<String>();
        var images = new ArrayList<String>();
        for (var part : content) {
            String type = part.path("type").asText();
            if ("text".equals(type) && part.path("text").isTextual()) {
                result.add(part.path("text").asText());
            } else if ("file".equals(type) && allowFiles) {
                files.add(documentAttachment(part.path("file"), attachments));
            } else if ("image_url".equals(type) && allowFiles) {
                images.add(imageDataUrl(part.path("image_url").path("url").asText()));
            } else {
                throw Store.error(400, "Only text blocks and user document or image attachments are supported");
            }
        }
        return new TextContent(result.toString(), files, images);
    }

    private static String imageDataUrl(String value) {
        int comma = value.indexOf(',');
        String header = comma < 0 ? "" : value.substring(0, comma);
        if (!header.matches("data:image/(?:png|jpeg|gif|webp);base64") || value.length() > 800000)
            throw Store.error(400, "image_url must contain a bounded base64 PNG, JPEG, GIF or WebP data URL");
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(value.substring(comma + 1));
        } catch (IllegalArgumentException invalid) {
            throw Store.error(400, "Invalid base64 image");
        }
        boolean valid = bytes.length >= 12 && switch (header) {
            case "data:image/png;base64" -> java.nio.ByteBuffer.wrap(bytes).getLong() == 0x89504e470d0a1a0aL;
            case "data:image/jpeg;base64" -> (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "data:image/gif;base64" -> valueOf(bytes, 0, 6).matches("GIF8[79]a");
            default -> "RIFF".equals(valueOf(bytes, 0, 4)) && "WEBP".equals(valueOf(bytes, 8, 4));
        };
        if (!valid) throw Store.error(400, "Image content does not match its declared format");
        return value;
    }

    private static String valueOf(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String documentAttachment(JsonNode file, Map<String, String> attachments) {
        if (attachments.size() == 4) throw Store.error(400, "At most four documents are supported");
        String data = file.path("file_data").asText();
        int comma = data.indexOf(',');
        String header = comma < 0 ? "" : data.substring(0, comma);
        boolean pdf = "data:application/pdf;base64".equals(header);
        if (!pdf && !header.matches("data:(?:text/[a-zA-Z0-9.+-]+|application/json);base64"))
            throw Store.error(400, "file_data must be a base64 text, JSON or PDF data URL");
        if (data.length() - comma > (pdf ? 800001 : 64001))
            throw Store.error(400, "Document attachment is too large");
        String text;
        try {
            var bytes = java.util.Base64.getDecoder().decode(data.substring(comma + 1));
            text = pdf ? pdfText(bytes) : java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | java.io.IOException invalid) {
            throw Store.error(400, "Document must be a valid base64-encoded UTF-8 text file or PDF");
        }
        if (text.isBlank() || text.indexOf(0) >= 0
                || text.length() + attachments.values().stream().mapToInt(String::length).sum() > 48000)
            throw Store.error(400, "Documents must contain extractable text and at most 48000 characters in total");
        String filename = file.path("filename").asText();
        if (!filename.matches("[\\p{L}0-9][\\p{L}0-9_. -]{0,78}") || filename.contains(".."))
            filename = "attachment";
        if (!filename.endsWith(".txt") && !filename.endsWith(".md")) filename += ".txt";
        String path = "attachments/" + (attachments.size() + 1) + "-" + filename;
        attachments.put(path, text);
        return path;
    }

    private static String pdfText(byte[] bytes) throws java.io.IOException {
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getNumberOfPages() > 20)
                throw Store.error(400, "PDF must be unencrypted and contain at most 20 pages");
            var text = new StringBuilder();
            var writer = new java.io.Writer() {
                @Override public void write(char[] buffer, int offset, int length) throws java.io.IOException {
                    if (text.length() + length > 48000) throw new java.io.IOException("PDF text exceeds the limit");
                    text.append(buffer, offset, length);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            new org.apache.pdfbox.text.PDFTextStripper().writeText(document, writer);
            return text.toString();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> invalid(ResponseStatusException problem) {
        String code = problem.getStatusCode().value() == 404 ? "model_not_found" : "invalid_request";
        return ResponseEntity.status(problem.getStatusCode()).body(error(problem.getReason(), "invalid_request_error", code));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> badJson() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("Invalid JSON", "invalid_request_error", "invalid_json"));
    }

    private static Map<String, Object> error(String message, String type, String code) {
        return Map.of("error", Map.of("message", message, "type", type, "code", code));
    }
}
