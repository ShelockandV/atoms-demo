package dev.atoms.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Ranks chunks from one authorized, immutable workspace version. */
@Component
@ConditionalOnProperty(name = "atoms.memory.documents.semantic-enabled", havingValue = "true")
public class SemanticDocuments {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SemanticDocuments.class);
    private static final int MAX_NEW_CHUNKS = 15;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SemanticHistory embeddings;

    public SemanticDocuments(JdbcTemplate db, ObjectMapper json, SemanticHistory embeddings) {
        this.db = db;
        this.json = json;
        this.embeddings = embeddings;
    }

    Map<DocumentIndex.ChunkKey, Double> rank(String version, String query,
                                             Map<DocumentIndex.ChunkKey, String> candidates) {
        if (candidates.isEmpty()) return Map.of();
        try {
            var vectors = cached(version);
            var missing = candidates.keySet().stream().filter(key -> !vectors.containsKey(key)).limit(MAX_NEW_CHUNKS).toList();
            var inputs = new ArrayList<String>();
            inputs.add(query);
            for (var key : missing) inputs.add(candidates.get(key));
            var batch = embeddings.embed(inputs);
            for (int i = 0; i < missing.size(); i++) {
                var key = missing.get(i);
                double[] vector = batch[i + 1];
                vectors.put(key, vector);
                try {
                    db.update("""
                            INSERT INTO workspace_document_vectors(version_id,file_number,chunk_number,embedding_profile,vector_json)
                            VALUES(?,?,?,?,?)
                            """, version, key.file(), key.chunk(), embeddings.profile(), json.writeValueAsString(vector));
                } catch (DuplicateKeyException indexedByPeer) {
                    // The same immutable chunk was indexed by another search.
                }
            }
            var scores = new HashMap<DocumentIndex.ChunkKey, Double>();
            for (var key : candidates.keySet()) {
                double score = SemanticHistory.cosine(batch[0], vectors.get(key));
                if (score >= embeddings.minimumSimilarity()) scores.put(key, score);
            }
            return scores;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException | DataAccessException | IllegalArgumentException unavailable) {
            log.debug("Semantic document search unavailable ({})", unavailable.getClass().getSimpleName());
        }
        return Map.of();
    }

    private Map<DocumentIndex.ChunkKey, double[]> cached(String version) {
        var vectors = new HashMap<DocumentIndex.ChunkKey, double[]>();
        db.query("""
                SELECT file_number,chunk_number,vector_json FROM workspace_document_vectors
                WHERE version_id=? AND embedding_profile=?
                """, rs -> {
            try {
                var key = new DocumentIndex.ChunkKey(rs.getInt("file_number"), rs.getInt("chunk_number"));
                vectors.put(key, json.readValue(rs.getString("vector_json"), double[].class));
            } catch (IOException malformed) {
                log.debug("Could not read document chunk embedding");
            }
        }, version, embeddings.profile());
        return vectors;
    }
}
