package dev.atoms.agent.skill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** 从运维指定的目录加载 YAML frontmatter 与 Markdown 正文，例如解析 {@code review@1.0.0}。 */
@Component
public final class SkillCatalog {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private final Map<String, List<Skill>> entries;

    public SkillCatalog(@Value("${atoms.agent.skills-directory:}") String directory) throws IOException {
        var loaded = new LinkedHashMap<String, List<Skill>>();
        if (!directory.isBlank()) {
            try (var files = Files.find(Path.of(directory), 4, (path, attributes) ->
                    attributes.isRegularFile() && path.getFileName().toString().endsWith(".md")
                            && !path.getFileName().toString().equalsIgnoreCase("README.md"))) {
                var paths = files.limit(201).toList();
                if (paths.size() > 200) throw new IllegalArgumentException("技能目录最多加载 200 个 Markdown 文件");
                for (var path : paths) {
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] bytes = input.readNBytes(65_537);
                        if (bytes.length > 65_536) throw new IllegalArgumentException("技能文件超过 64 KiB：" + path.getFileName());
                        var skill = parse(new String(bytes, StandardCharsets.UTF_8));
                        var versions = loaded.computeIfAbsent(skill.name(), ignored -> new ArrayList<>());
                        if (versions.stream().anyMatch(other -> other.version().equals(skill.version())))
                            throw new IllegalArgumentException("重复技能版本：" + skill.reference());
                        versions.add(skill);
                    }
                }
            }
        }
        loaded.replaceAll((name, versions) -> versions.stream()
                .sorted(Comparator.comparing(Skill::version, SkillCatalog::compareVersions).reversed()).toList());
        entries = Map.copyOf(loaded);
    }

    public List<Skill> available() {
        return entries.values().stream().flatMap(Collection::stream).filter(Skill::enabled)
                .sorted(Comparator.comparing(Skill::name)
                        .thenComparing(Skill::version, (left, right) -> compareVersions(right, left)))
                .toList();
    }

    public Skill resolve(String reference) {
        var parts = reference.split("@", -1);
        if (parts.length > 2 || !NAME.matcher(parts[0]).matches()
                || parts.length == 2 && !VERSION.matcher(parts[1]).matches())
            throw new IllegalArgumentException("技能引用须为 name 或 name@X.Y.Z");
        return entries.getOrDefault(parts[0], List.of()).stream().filter(Skill::enabled)
                .filter(skill -> parts.length == 1 || skill.version().equals(parts[1]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("技能不存在或已禁用：" + reference));
    }

    static Skill parse(String markdown) {
        var blocks = markdown.split("(?m)^---[ \\t]*\\r?$", 3);
        if (blocks.length != 3 || !blocks[0].isBlank() || blocks[2].isBlank())
            throw new IllegalArgumentException("技能文件需要 YAML frontmatter 和 Markdown 正文");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(12);
        Object data = new Yaml(new SafeConstructor(options)).load(blocks[1]);
        if (!(data instanceof Map<?, ?> fields)) throw new IllegalArgumentException("技能 frontmatter 必须是映射");
        String name = string(fields, "name", "");
        String version = string(fields, "version", "1.0.0");
        if (!NAME.matcher(name).matches() || !VERSION.matcher(version).matches())
            throw new IllegalArgumentException("技能名称或三段版本号无效");
        boolean dangerous = bool(fields, "dangerous", false);
        String description = string(fields, "description", "");
        if (dangerous && description.isBlank()) throw new IllegalArgumentException("危险技能须填写说明");
        Object budget = fields.get("budget_max");
        if (budget != null && (!(budget instanceof Integer amount) || amount < 0))
            throw new IllegalArgumentException("技能 budget_max 必须是非负整数");
        var tools = new ArrayList<String>();
        if (fields.containsKey("requires_tools")) {
            if (!(fields.get("requires_tools") instanceof List<?> values) || values.size() > 40)
                throw new IllegalArgumentException("技能 requires_tools 必须为工具名列表");
            for (Object value : values) {
                if (!(value instanceof String tool) || !NAME.matcher(tool).matches())
                    throw new IllegalArgumentException("技能所需工具名无效");
                tools.add(tool);
            }
        }
        var metadata = new LinkedHashMap<String, Object>();
        if (fields.containsKey("metadata")) {
            if (!(fields.get("metadata") instanceof Map<?, ?> source))
                throw new IllegalArgumentException("技能 metadata 必须是映射");
            source.forEach((key, value) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("技能 metadata 键必须是字符串");
                metadata.put(text, value);
            });
        }
        return new Skill(name, version, string(fields, "author", ""), string(fields, "category", ""),
                description, tools, string(fields, "requires_role", ""), budget == null ? 0 : ((Integer) budget).longValue(),
                dangerous, bool(fields, "enabled", true), metadata, blocks[2].strip());
    }

    private static int compareVersions(String left, String right) {
        var a = left.split("\\.");
        var b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static String string(Map<?, ?> fields, String key, String fallback) {
        if (!fields.containsKey(key)) return fallback;
        if (fields.get(key) instanceof String value) return value;
        throw new IllegalArgumentException("技能字段须为字符串：" + key);
    }

    private static boolean bool(Map<?, ?> fields, String key, boolean fallback) {
        if (!fields.containsKey(key)) return fallback;
        if (fields.get(key) instanceof Boolean value) return value;
        throw new IllegalArgumentException("技能字段须为布尔值：" + key);
    }
}
