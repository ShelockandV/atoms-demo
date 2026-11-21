package dev.atoms.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Builds a reusable overlapping text index for authorized, immutable project snapshots. */
public final class DocumentIndex {
    private static final int CHUNK_SIZE = 800;
    private static final int OVERLAP = 120;
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("md", "markdown", "txt", "rst", "adoc");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public DocumentIndex(JdbcTemplate db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    public record Hit(String path, int chunk, int start, int end, String text) {}
    record ChunkKey(int file, int chunk) {}
    private record Chunk(ChunkKey key, Hit hit) {}
    private record Match(Chunk chunk, double score) {}
    private static final Comparator<Match> RANK = Comparator.comparingDouble(Match::score).reversed()
            .thenComparing(match -> match.chunk().hit().path()).thenComparingInt(match -> match.chunk().hit().chunk());

    public List<Hit> search(String version, String query, Supplier<Map<String, String>> files,
                            SemanticDocuments semantic) {
        if (version == null) return List.of();
        if (!indexed(version)) index(version, files);
        var similarity = new PromptSimilarity(query);
        String phrase = normalize(query);
        var chunks = db.query("""
                SELECT file_number,file_path,chunk_number,start_offset,end_offset,content
                FROM workspace_document_chunks WHERE version_id=?
                """, (rs, row) -> new Chunk(new ChunkKey(rs.getInt("file_number"), rs.getInt("chunk_number")),
                new Hit(rs.getString("file_path"), rs.getInt("chunk_number"),
                        rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("content"))), version);
        var lexical = chunks.stream().map(chunk -> {
                    String text = normalize(chunk.hit().text());
                    String path = normalize(chunk.hit().path());
                    double coverage = similarity.coverage(text);
                    double score = text.contains(phrase) ? 3 : path.contains(phrase) ? 2 + coverage
                            : Math.max(coverage, similarity.coverage(path));
                    return new Match(chunk, score);
                }).sorted(RANK).toList();
        if (semantic == null) return hits(lexical);
        var candidates = new LinkedHashMap<ChunkKey, String>();
        for (var match : lexical) {
            var chunk = match.chunk();
            if (!chunk.hit().text().isBlank()) candidates.put(chunk.key(), chunk.hit().text());
        }
        var vectorScores = semantic.rank(version, query, candidates);
        var hybrid = lexical.stream().map(match -> {
            Double score = vectorScores.get(match.chunk().key());
            return score == null ? match : new Match(match.chunk(), Math.max(match.score(), 1 + score));
        }).sorted(RANK).toList();
        return hits(hybrid);
    }

    private static List<Hit> hits(List<Match> matches) {
        return matches.stream().filter(match -> match.score() >= 0.45)
                .limit(5).map(match -> match.chunk().hit()).toList();
    }

    private boolean indexed(String version) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM workspace_document_indexes WHERE version_id=?)", Boolean.class, version));
    }

    private void index(String version, Supplier<Map<String, String>> files) {
        tx.executeWithoutResult(status -> {
            db.queryForList("SELECT id FROM project_versions WHERE id=? FOR UPDATE", version);
            if (indexed(version)) return;
            db.update("INSERT INTO workspace_document_indexes(version_id) VALUES(?)", version);
            var rows = new ArrayList<Object[]>();
            int fileNumber = 0;
            for (var file : new TreeMap<>(files.get()).entrySet()) {
                String path = file.getKey();
                String content = file.getValue();
                int dot = path.lastIndexOf('.');
                if (dot < 0 || !DOCUMENT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT))
                        || content.isBlank()) continue;
                fileNumber++;
                var points = content.codePoints().toArray();
                for (int start = 0, chunk = 0; start < points.length; start += CHUNK_SIZE - OVERLAP, chunk++) {
                    int end = Math.min(start + CHUNK_SIZE, points.length);
                    rows.add(new Object[]{version, fileNumber, path, chunk, start, end, new String(points, start, end - start)});
                    if (end == points.length) break;
                }
            }
            if (!rows.isEmpty()) db.batchUpdate("""
                    INSERT INTO workspace_document_chunks(version_id,file_number,file_path,chunk_number,start_offset,end_offset,content)
                    VALUES(?,?,?,?,?,?,?)
                    """, rows);
        });
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
