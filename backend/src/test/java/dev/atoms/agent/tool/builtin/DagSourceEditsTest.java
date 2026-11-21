package dev.atoms.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DagSourceEditsTest {
    @Test void mergesDisjointAndIdenticalEditsAndPreservesUntouchedFiles() {
        var baseline = Map.of("README.md", "keep", "old.txt", "remove", "app.js", "old");
        var result = DagSourceEdits.merge(baseline, List.of(
                "{\"app.js\":\"new\",\"old.txt\":null}", "{\"style.css\":\"body{}\",\"app.js\":\"new\"}"));
        assertEquals(Map.of("README.md", "keep", "app.js", "new", "style.css", "body{}"), result);
        assertEquals("old", baseline.get("app.js"));
    }

    @Test void detectsConflictsInvalidPathsAndDuplicateJsonKeys() {
        var baseline = Map.of("src/App.java", "original");
        for (var edits : List.of(
                List.of("{\"src/App.java\":\"left\"}", "{\"src/App.java\":\"right\"}"),
                List.of("{\"src/app.java\":\"case\"}"),
                List.of("{\"../outside\":\"invalid\"}"),
                List.of("{\"ok.txt\":\"first\",\"ok.txt\":\"duplicate\"}"))) {
            assertThrows(IllegalArgumentException.class, () -> DagSourceEdits.merge(baseline, edits));
        }
    }

    @Test void resumesSavedNodesAndReconcilesConflictsBeforePublishingCandidate() throws Exception {
        var context = mock(ToolContext.class);
        var mapper = new ObjectMapper();
        var rows = new ConcurrentHashMap<String, Map<String, Object>>();
        rows.put("v3.edits.left", Map.of("id", "v3.edits.left", "status", "SUCCEEDED",
                "output", "{\"app.js\":\"left\"}"));
        when(context.nodes()).thenAnswer(invocation -> new ArrayList<>(rows.values()));
        when(context.track()).thenReturn(() -> {});
        when(context.encode(any())).thenAnswer(invocation -> mapper.writeValueAsString(invocation.getArgument(0)));
        when(context.baseline()).thenReturn(Map.of("app.js", "original"));
        when(context.proposeDagEdit(eq("v3.edits.right"), contains("left"))).thenReturn("{\"app.js\":\"right\"}");
        when(context.generate(contains("多个节点对同一文件的修改冲突：app.js")))
                .thenReturn(Map.of("app.js", "reconciled"));
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            rows.put(id, Map.of("id", id, "status", invocation.getArgument(2), "output", invocation.getArgument(3)));
            return null;
        }).when(context).save(anyString(), anyList(), anyString(), anyString(), anyLong());

        var result = DagSourceEdits.generate(context, "", """
                [{"id":"left","task":"edit left","dependsOn":[]},
                 {"id":"right","task":"edit right","dependsOn":["left"]}]
                """);
        assertEquals(Map.of("app.js", "reconciled"), result);
        verify(context, never()).proposeDagEdit(eq("v3.edits.left"), anyString());
        assertEquals("SUCCEEDED", rows.get("v3.edits.right").get("status"));
    }
}
