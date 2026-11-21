package dev.atoms.workspace;

import dev.atoms.agent.persistence.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public final class GatewayMemoryFiles {
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_RECALL_CHARACTERS = 8000;
    private final Path root;
    private final boolean recallEnabled;
    private final boolean autoWrite;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private static final String EXTRACTED = "extracted";

    GatewayMemoryFiles(@Value("${atoms.memory.user-files.directory:./data/user-memory}") String directory,
                       @Value("${atoms.memory.user-files.recall-enabled:false}") boolean recallEnabled,
                       @Value("${atoms.memory.user-files.auto-write-enabled:false}") boolean autoWrite,
                       org.springframework.jdbc.core.JdbcTemplate db) {
        this.db = db;
        this.autoWrite = autoWrite;
        this.recallEnabled = recallEnabled;
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public String captureScope(String scope) {
        return autoWrite ? scope : null;
    }

    public String recall(String scope) {
        if (!recallEnabled || scope == null) return "";
        var extracted = db.queryForList("""
                SELECT content FROM gateway_memory_entries WHERE memory_scope=?
                ORDER BY created_at DESC,run_id DESC LIMIT 8
                """, String.class, scope);
        var memory = new ArrayList<String>();
        for (String filename : List.of("MEMORY.md", "memory.md")) {
            try {
                String content = (String) read(scope, filename).get("content");
                if (!content.isBlank()) memory.add(limit(content, extracted.isEmpty() ? MAX_RECALL_CHARACTERS : 4000));
                break;
            } catch (org.springframework.web.server.ResponseStatusException unavailable) {
                if (unavailable.getStatusCode().value() != 404) break;
            }
        }
        memory.addAll(extracted);
        return limit(String.join("\n\n", memory), MAX_RECALL_CHARACTERS);
    }

    private static String limit(String text, int maximum) {
        int length = text.codePointCount(0, text.length());
        return text.substring(0, text.offsetByCodePoints(0, Math.min(maximum, length))).strip();
    }

    private List<Entry> extractedEntries(String scope) {
        return db.query("""
                SELECT run_id,content FROM gateway_memory_entries WHERE memory_scope=?
                ORDER BY created_at DESC,run_id DESC LIMIT 1000
                """, (row, index) -> {
            String name = row.getString("run_id") + ".md";
            return new Entry(name, EXTRACTED + "/" + name, false,
                    row.getString("content").getBytes(StandardCharsets.UTF_8).length);
        }, scope);
    }

    public record Entry(String name, String path, @com.fasterxml.jackson.annotation.JsonProperty("is_dir") boolean directory,
                        @com.fasterxml.jackson.annotation.JsonProperty("size_bytes") long size) {}

    public List<Entry> list(String owner, String directory) {
        if (EXTRACTED.equals(directory)) {
            var generated = extractedEntries(owner);
            if (!generated.isEmpty()) return generated;
        }
        var files = listFiles(owner, directory);
        if (directory.isEmpty() && !db.queryForList("""
                SELECT run_id FROM gateway_memory_entries WHERE memory_scope=? LIMIT 1
                """, String.class, owner).isEmpty()) {
            var merged = new ArrayList<>(files);
            merged.removeIf(entry -> EXTRACTED.equals(entry.name()));
            merged.add(new Entry(EXTRACTED, EXTRACTED, true, 0));
            merged.sort(Comparator.comparing(Entry::name));
            return merged;
        }
        return files;
    }

    private List<Entry> listFiles(String owner, String directory) {
        var relative = directory.isEmpty() ? List.<String>of() : segments(directory);
        try {
            var dir = memoryDirectory(owner);
            for (String segment : relative) dir = directory(dir.resolve(segment));
            return listAt(dir, relative);
        } catch (NoSuchFileException | NotDirectoryException missing) {
            if (relative.isEmpty()) return List.of();
            throw Store.error(404, "记忆目录不存在");
        } catch (IOException failure) {
            throw Store.error(503, "无法读取记忆目录");
        }
    }

    public Map<String, Object> read(String owner, String file) {
        var relative = segments(file);
        if (relative.size() == 2 && EXTRACTED.equals(relative.getFirst()) && relative.getLast().endsWith(".md")) {
            String id = relative.getLast().substring(0, relative.getLast().length() - 3);
            var rows = db.queryForList("SELECT content FROM gateway_memory_entries WHERE memory_scope=? AND run_id=?",
                    String.class, owner, id);
            if (!rows.isEmpty()) {
                String content = rows.getFirst();
                return Map.of("success", true, "content", content, "content_type", "text/markdown; charset=utf-8",
                        "size_bytes", content.getBytes(StandardCharsets.UTF_8).length);
            }
        }
        try {
            var dir = memoryDirectory(owner);
            for (String segment : relative.subList(0, relative.size() - 1)) dir = directory(dir.resolve(segment));
            return readAt(dir.resolve(relative.getLast()));
        } catch (NoSuchFileException | NotDirectoryException | AccessDeniedException missing) {
            throw Store.error(404, "记忆文件不存在");
        } catch (IOException failure) {
            throw Store.error(503, "无法读取记忆文件");
        }
    }

    private List<Entry> listAt(Path dir, List<String> relative) throws IOException {
        String prefix = relative.isEmpty() ? "" : String.join("/", relative) + "/";
        var entries = new ArrayList<Entry>();
        try (var children = Files.newDirectoryStream(dir)) {
            for (var path : children) {
                String filename = path.getFileName().toString();
                if ("lost+found".equals(filename) || !validSegment(filename)) continue;
                BasicFileAttributes attributes;
                try {
                    attributes = attributes(path);
                } catch (NoSuchFileException removed) {
                    continue;
                }
                if (attributes.isDirectory() || attributes.isRegularFile()) {
                    if (entries.size() == MAX_ENTRIES) throw Store.error(413, "记忆目录内容过多");
                    entries.add(new Entry(filename, prefix + filename, attributes.isDirectory(), attributes.size()));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    private Map<String, Object> readAt(Path file) throws IOException {
        var attributes = attributes(file);
        if (attributes.isDirectory()) throw Store.error(400, "该路径是目录");
        if (!attributes.isRegularFile()) throw Store.error(404, "记忆文件不存在");
        if (attributes.size() > MAX_FILE_BYTES) throw Store.error(413, "记忆文件过大");
        byte[] data;
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            data = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (data.length > MAX_FILE_BYTES) throw Store.error(413, "记忆文件过大");
        String type = contentType(file.getFileName().toString());
        boolean text = type.startsWith("text/") || Set.of("application/json", "application/xml").contains(type);
        return Map.of("success", true, "content_type", type, "size_bytes", data.length,
                "content", text ? new String(data, StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(data));
    }

    private Path memoryDirectory(String owner) throws IOException {
        if (!owner.matches("[gu]-[0-9a-f-]{34,64}")) throw Store.error(400, "无效记忆目录身份");
        Path base = directory(root.toRealPath());
        return directory(directory(base.resolve(owner)).resolve("memory"));
    }

    private static Path directory(Path path) throws IOException {
        if (!attributes(path).isDirectory()) throw new NotDirectoryException(path.toString());
        Path real = path.toRealPath();
        if (!real.equals(path)) throw new NotDirectoryException(path.toString());
        return real;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static List<String> segments(String path) {
        var pieces = List.of(path.split("/", -1));
        if (path.length() > 240 || pieces.size() > 32 || pieces.stream().anyMatch(piece -> !validSegment(piece)))
            throw Store.error(400, "须提供有效的记忆文件相对路径");
        return pieces;
    }

    private static boolean validSegment(String segment) {
        return !segment.isBlank() && !segment.equals(".") && !segment.equals("..")
                && segment.indexOf('\\') < 0 && segment.indexOf(':') < 0
                && segment.codePoints().noneMatch(Character::isISOControl);
    }

    private static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "text/yaml; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json";
        String type = java.net.URLConnection.guessContentTypeFromName(lower);
        if (type == null) return "application/octet-stream";
        return type.startsWith("text/") ? type + "; charset=utf-8" : type;
    }

}
