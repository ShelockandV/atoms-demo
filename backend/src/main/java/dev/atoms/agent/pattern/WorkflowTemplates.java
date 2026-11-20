package dev.atoms.agent.pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 加载运维维护的 YAML 模板，例如通过 project_review 选择已注册的项目复核模板。 */
@Component
public final class WorkflowTemplates {
    private final Map<String, WorkflowTemplate> entries;

    public WorkflowTemplates(@Value("${atoms.agent.workflow-templates-directory:}") String directory) throws IOException {
        var definitions = new TreeMap<String, Definition>();
        if (!directory.isBlank()) {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setNestingDepthLimit(16);
            options.setMaxAliasesForCollections(0);
            var yaml = new Yaml(new SafeConstructor(options));
            var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            try (var files = Files.find(Path.of(directory), 2, (path, attributes) -> attributes.isRegularFile()
                    && (path.toString().endsWith(".yaml") || path.toString().endsWith(".yml")))) {
                var paths = files.limit(101).toList();
                if (paths.size() > 100) throw new IllegalArgumentException("模板目录最多加载 100 个文件");
                for (var path : paths) {
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] bytes = input.readNBytes(65_537);
                        if (bytes.length > 65_536) throw new IllegalArgumentException("模板文件超过 64 KiB");
                        var template = mapper.convertValue(yaml.load(new String(bytes, StandardCharsets.UTF_8)), Definition.class);
                        if (template == null || definitions.putIfAbsent(template.key(), template) != null)
                            throw new IllegalArgumentException("模板为空或名称重复：" + path.getFileName());
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("模板加载失败：" + path.getFileName(), e);
                    }
                }
            }
        }
        var resolved = new TreeMap<String, WorkflowTemplate>();
        for (String key : definitions.keySet()) resolve(key, definitions, resolved, new LinkedHashSet<>());
        entries = Collections.unmodifiableMap(resolved);
    }

    public List<Map<String, String>> catalog() {
        return entries.entrySet().stream().map(entry -> {
            var template = entry.getValue();
            return Map.of("id", entry.getKey(), "name", template.name(),
                    "description", template.description(), "version", template.version());
        }).toList();
    }

    public WorkflowTemplate require(String name) {
        return entries.get(findKey(name, entries.keySet()));
    }

    public WorkflowTemplate route(String name, String version, boolean requireTemplate) {
        String reference = name == null ? "" : name.strip();
        String requestedVersion = version == null ? "" : version.strip();
        if (!requestedVersion.isEmpty()) {
            if (reference.isEmpty() || reference.contains("@") || !requestedVersion.matches("[a-zA-Z0-9_.+-]{1,64}"))
                throw new IllegalArgumentException("模板版本须与独立的模板名称一起提交");
            reference += "@" + requestedVersion;
        }
        String key = matchingKey(reference, entries.keySet());
        if (key != null) return entries.get(key);
        if (requireTemplate) throw new IllegalArgumentException("模板必选，但没有匹配的模板：" + reference);
        return null;
    }

    private static String findKey(String reference, Set<String> keys) {
        String key = matchingKey(reference.strip(), keys);
        if (key != null) return key;
        throw new IllegalArgumentException("未知工作流模板：" + reference);
    }

    private static String matchingKey(String name, Set<String> keys) {
        if (keys.contains(name)) return name;
        if (name.isEmpty() || name.contains("@")) return null;
        return keys.stream().filter(candidate -> candidate.startsWith(name + "@")).max(Comparator.naturalOrder()).orElse(null);
    }

    private static WorkflowTemplate resolve(String key, Map<String, Definition> definitions,
                                            Map<String, WorkflowTemplate> resolved, Set<String> visiting) {
        var cached = resolved.get(key);
        if (cached != null) return cached;
        if (!visiting.add(key)) throw new IllegalArgumentException("工作流模板存在继承循环：" + visiting + " → " + key);
        var definition = definitions.get(key);
        var nodes = new LinkedHashMap<String, WorkflowTemplate.Node>();
        List<WorkflowTemplate.Edge> edges = List.of();
        WorkflowTemplate.Defaults defaults = null;
        for (String parent : definition.parents()) {
            var inherited = resolve(findKey(parent, definitions.keySet()), definitions, resolved, visiting);
            mergeNodes(nodes, inherited.nodes());
            if (!inherited.edges().isEmpty()) edges = inherited.edges();
            defaults = mergeDefaults(defaults, inherited.defaults());
        }
        mergeNodes(nodes, definition.nodes());
        if (!definition.edges().isEmpty()) edges = definition.edges();
        defaults = mergeDefaults(defaults, definition.defaults());
        var template = new WorkflowTemplate(definition.name(), definition.description(), definition.version(),
                List.copyOf(nodes.values()), edges, defaults);
        resolved.put(key, template);
        visiting.remove(key);
        return template;
    }

    private static WorkflowTemplate.Defaults mergeDefaults(WorkflowTemplate.Defaults base, WorkflowTemplate.Defaults child) {
        if (base == null || child == null) return child == null ? base : child;
        return new WorkflowTemplate.Defaults(child.budgetAgentMax() > 0 ? child.budgetAgentMax() : base.budgetAgentMax(),
                child.requireApproval() == null ? base.requireApproval() : child.requireApproval());
    }

    private static void mergeNodes(Map<String, WorkflowTemplate.Node> nodes, List<WorkflowTemplate.Node> overlay) {
        for (var node : overlay) {
            nodes.merge(node.id(), node, (base, child) -> new WorkflowTemplate.Node(
                    child.id(), child.type() == null || child.type().isEmpty() ? base.type() : child.type(),
                    child.dependsOn().isEmpty() ? base.dependsOn() : child.dependsOn(),
                    mergeMetadata(base.metadata(), child.metadata()),
                    child.budgetMax() == null ? base.budgetMax() : child.budgetMax(),
                    child.onFail() == null ? base.onFail() : child.onFail(),
                    child.strategy() == null || child.strategy().isEmpty() ? base.strategy() : child.strategy(),
                    child.toolsAllowlist().isEmpty() ? base.toolsAllowlist() : child.toolsAllowlist()));
        }
    }

    private static WorkflowTemplate.Metadata mergeMetadata(WorkflowTemplate.Metadata base, WorkflowTemplate.Metadata child) {
        if (base == null || child == null) return child == null ? base : child;
        return new WorkflowTemplate.Metadata(child.query() == null ? base.query() : child.query(),
                child.tasks().isEmpty() ? base.tasks() : child.tasks(),
                child.execution() == null ? base.execution() : child.execution(),
                child.maxConcurrency() == null ? base.maxConcurrency() : child.maxConcurrency(),
                child.roleTools().isEmpty() ? base.roleTools() : child.roleTools());
    }

    /** 保存 YAML 中尚未展开的继承声明，例如 extends: [review@1.0.0]。 */
    private record Definition(String name, String description, String version,
                              @com.fasterxml.jackson.annotation.JsonProperty("extends") List<String> parents,
                              List<WorkflowTemplate.Node> nodes, List<WorkflowTemplate.Edge> edges,
                              WorkflowTemplate.Defaults defaults) {
        private Definition {
            if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}"))
                throw new IllegalArgumentException("模板名称无效");
            version = version == null || version.isBlank() ? null : version.strip();
            if (version != null && !version.matches("[a-zA-Z0-9_.+-]{1,64}"))
                throw new IllegalArgumentException("模板版本无效");
            parents = parents == null ? List.of() : List.copyOf(parents);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            var ids = new HashSet<String>();
            for (var node : nodes)
                if (!ids.add(node.id())) throw new IllegalArgumentException("模板节点重复：" + node.id());
        }

        String key() { return version == null ? name : name + "@" + version; }
    }
}
