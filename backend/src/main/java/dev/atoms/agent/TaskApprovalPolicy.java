package dev.atoms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.pattern.WorkflowTemplate;
import dev.atoms.agent.persistence.Store;
import dev.atoms.agent.skill.Skill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

@Service
public final class TaskApprovalPolicy {
    private final URI endpoint;
    private final URI toolEndpoint;
    private final java.util.Set<String> approvalTools;
    private final String environment;
    private final long tokenBudget;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    TaskApprovalPolicy(ObjectMapper json, @Value("${atoms.approval.opa-url:}") String url,
                       @Value("${atoms.approval.environment:dev}") String environment,
                       @Value("${atoms.agent.token-budget:0}") long tokenBudget,
                       @Value("${atoms.approval.tool-opa-url:}") String toolUrl,
                       @Value("${atoms.approval.tools-requiring-approval:}") String tools) {
        this.json = json;
        this.environment = environment;
        this.tokenBudget = tokenBudget;
        endpoint = policyEndpoint(url);
        toolEndpoint = policyEndpoint(toolUrl);
        approvalTools = java.util.Arrays.stream(tools.split(",")).map(String::strip)
                .filter(name -> !name.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static URI policyEndpoint(String url) {
        if (url.isBlank()) return null;
        URI endpoint = URI.create(url.strip());
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !endpoint.getPath().startsWith("/v1/data/"))
            throw new IllegalArgumentException("OPA 地址须指向 HTTP(S) /v1/data/ 策略");
        return endpoint;
    }

    String toolRequirement(String owner, String run, int step, String tool) {
        String reason = toolEndpoint == null ? null : evaluate(toolEndpoint, Map.of(
                "environment", environment, "user_id", owner, "workflow_id", run, "step", step,
                "tool", tool, "tools_to_use", java.util.List.of(tool)));
        return reason != null ? reason : approvalTools.contains(tool) ? "此工具需要人工审批：" + tool : null;
    }

    String nodeToolRequirement(String owner, String run, String node, String tool) {
        String reason = toolEndpoint == null ? null : evaluate(toolEndpoint, Map.of(
                "environment", environment, "user_id", owner, "workflow_id", run, "node", node,
                "tool", tool, "tools_to_use", java.util.List.of(tool)));
        return reason != null ? reason : approvalTools.contains(tool) ? "此节点工具需要人工审批：" + tool : null;
    }

    boolean evaluatesTool(String name) { return toolEndpoint != null || approvalTools.contains(name); }

    public String requirement(String owner, String query, String mode, String model, String pattern,
                       Skill skill, WorkflowTemplate template) {
        return requirement(owner, query, mode, model, pattern, skill, template, java.util.List.of());
    }

    public String requirement(String owner, String query, String mode, String model, String pattern,
                              Skill skill, WorkflowTemplate template, java.util.List<String> requiredTools) {
        if (endpoint == null) return null;
        var input = new LinkedHashMap<String, Object>();
        input.put("environment", environment);
        input.put("user_id", owner);
        input.put("query", query);
        input.put("mode", template != null || "dag".equals(pattern) || "research".equals(mode)
                || "openai-complex".equals(mode) ? "complex" : "build".equals(mode) ? "standard" : "simple");
        input.put("atoms_mode", mode);
        input.put("model", model);
        input.put("pattern", pattern);
        long skillBudget = skill == null ? 0 : skill.budgetMax();
        input.put("token_budget", tokenBudget == 0 ? skillBudget : skillBudget == 0 ? tokenBudget : Math.min(tokenBudget, skillBudget));
        var tools = new LinkedHashSet<>(requiredTools);
        if (skill != null) {
            input.put("skill", skill.reference());
            tools.addAll(skill.requiresTools());
        }
        if (template != null) {
            input.put("template", template.name());
            template.nodes().forEach(node -> tools.addAll(node.toolsAllowlist()));
        }
        input.put("tools_to_use", tools);
        return evaluate(endpoint, input);
    }

    private String evaluate(URI endpoint, Map<String, ?> input) {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("input", input)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 32000) throw unavailable();
            var envelope = json.readTree(response.body());
            if (envelope == null) throw unavailable();
            var decision = envelope.path("result");
            if (!decision.path("allow").isBoolean()
                    || (decision.has("require_approval") && !decision.path("require_approval").isBoolean())) throw unavailable();
            if (!decision.path("allow").asBoolean()) throw Store.error(403, "审批策略拒绝该任务");
            if (!decision.path("require_approval").asBoolean()) return null;
            String reason = decision.path("reason").asText("策略要求人工审批").strip();
            if (reason.isEmpty()) return "策略要求人工审批";
            return reason.length() <= 500 ? reason : reason.substring(0, reason.offsetByCodePoints(0,
                    Math.min(250, reason.codePointCount(0, reason.length()))));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException unavailable) {
            throw unavailable();
        }
    }

    private static org.springframework.web.server.ResponseStatusException unavailable() {
        return Store.error(503, "审批策略服务不可用");
    }
}
