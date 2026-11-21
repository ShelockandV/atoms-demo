package dev.atoms.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indexes a bounded set of project prompts and scores them against the current request. */
@Component
@ConditionalOnProperty(name = "atoms.memory.semantic.enabled", havingValue = "true")
public class SemanticHistory {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SemanticHistory.class);
    private static final int MAX_NEW_INPUTS = 16;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI endpoint;
    private final String key;
    private final String model;
    private final String profile;
    private final double minimumSimilarity;

    public SemanticHistory(JdbcTemplate db, ObjectMapper json,
                           @Value("${atoms.memory.semantic.endpoint}") URI endpoint,
                           @Value("${atoms.memory.semantic.api-key:}") String key,
                           @Value("${atoms.memory.semantic.model:text-embedding-3-small}") String model,
                           @Value("${atoms.memory.semantic.minimum-similarity:0.65}") double minimumSimilarity) {
        if (endpoint.getHost() == null || !List.of("https", "http").contains(endpoint.getScheme())
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null)
            throw new IllegalArgumentException("Embedding endpoint 须为完整 HTTP(S) 接口地址");
        if (model.isBlank() || !Double.isFinite(minimumSimilarity) || minimumSimilarity < 0 || minimumSimilarity > 1)
            throw new IllegalArgumentException("Embedding 模型或相似度配置无效");
        this.db = db;
        this.json = json;
        this.endpoint = endpoint;
        this.key = key;
        this.model = model;
        this.profile = profile(endpoint + "\n" + model);
        this.minimumSimilarity = minimumSimilarity;
    }

    public Map<String, Double> rank(String project, String runId, String prompt, Map<String, String> candidates) {
        if (candidates.isEmpty() || prompt.isBlank()) return Map.of();
        try {
            var vectors = cached(project, runId, candidates);
            var missing = new LinkedHashMap<String, String>();
            if (!vectors.containsKey(runId)) missing.put(runId, prompt);
            for (var entry : candidates.entrySet()) {
                if (missing.size() >= MAX_NEW_INPUTS) break;
                if (!vectors.containsKey(entry.getKey())) missing.put(entry.getKey(), entry.getValue());
            }
            if (!missing.isEmpty()) {
                var ids = new ArrayList<>(missing.keySet());
                var generated = embed(missing.values().stream().map(SemanticHistory::clip).toList());
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i);
                    double[] vector = generated[i];
                    vectors.put(id, vector);
                    try {
                        db.update("INSERT INTO project_prompt_embeddings(run_id,embedding_profile,vector_json) VALUES(?,?,?)",
                                id, profile, json.writeValueAsString(vector));
                    } catch (DuplicateKeyException indexedByAnotherWorker) {
                        // Both workers used the same provider profile and immutable run prompt.
                    }
                }
            }
            double[] query = vectors.get(runId);
            if (query == null) return Map.of();
            var scores = new HashMap<String, Double>();
            for (String candidate : candidates.keySet()) {
                double score = cosine(query, vectors.get(candidate));
                if (score >= minimumSimilarity) scores.put(candidate, score);
            }
            return scores;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException | org.springframework.dao.DataAccessException | IllegalArgumentException unavailable) {
            log.debug("Semantic project history unavailable ({})", unavailable.getClass().getSimpleName());
        }
        return Map.of();
    }

    private Map<String, double[]> cached(String project, String runId, Map<String, String> candidates) {
        var ids = new ArrayList<Object>(List.of(profile, project, runId));
        ids.addAll(candidates.keySet());
        String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size() + 1, "?"));
        var vectors = new HashMap<String, double[]>();
        db.query("""
                SELECT e.run_id,e.vector_json FROM project_prompt_embeddings e
                JOIN generation_runs r ON r.id=e.run_id
                WHERE e.embedding_profile=? AND r.project_id=? AND e.run_id IN (
                """ + placeholders + ")", rs -> {
            try {
                vectors.put(rs.getString("run_id"), json.readValue(rs.getString("vector_json"), double[].class));
            } catch (IOException malformed) {
                log.debug("Could not read cached embedding");
            }
        }, ids.toArray());
        return vectors;
    }

    String profile() {
        return profile;
    }

    double minimumSimilarity() {
        return minimumSimilarity;
    }

    static double cosine(double[] query, double[] vector) {
        if (query == null || vector == null || query.length != vector.length) return Double.NEGATIVE_INFINITY;
        double score = 0;
        for (int i = 0; i < query.length; i++) score += query[i] * vector[i];
        return score;
    }

    double[][] embed(List<String> inputs) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model", model, "input", inputs))));
        if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 2_000_000)
            throw new IOException("Embedding response unavailable");
        var root = json.readTree(response.body());
        if (root == null || !root.path("data").isArray()) throw new IOException("Invalid embedding response");
        var result = new double[inputs.size()][];
        int expectedDimension = 0;
        for (var item : root.path("data")) {
            var index = item.path("index");
            if (!index.canConvertToInt() || index.asInt() < 0 || index.asInt() >= result.length
                    || result[index.asInt()] != null) throw new IOException("Invalid embedding index");
            double[] vector = normalized(item.path("embedding"));
            if (expectedDimension != 0 && vector.length != expectedDimension) throw new IOException("Inconsistent dimensions");
            expectedDimension = vector.length;
            result[index.asInt()] = vector;
        }
        for (var vector : result) if (vector == null) throw new IOException("Incomplete embedding response");
        return result;
    }

    private static double[] normalized(JsonNode values) throws IOException {
        if (!values.isArray() || values.isEmpty() || values.size() > 4096) throw new IOException("Invalid dimensions");
        double[] vector = new double[values.size()];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            if (!values.get(i).isNumber()) throw new IOException("Invalid embedding value");
            vector[i] = values.get(i).asDouble();
            norm = Math.hypot(norm, vector[i]);
        }
        if (!Double.isFinite(norm) || norm == 0) throw new IOException("Invalid embedding norm");
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private static String clip(String value) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(1600, value.codePointCount(0, value.length()))));
    }

    private static String profile(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
