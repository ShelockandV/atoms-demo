package dev.atoms.agent.runtime;

import dev.atoms.agent.runtime.ResearchWorkflow.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ResearchSynthesisTest {
    @Test void shortAliasesMatchWholeWordsAndOfficialHostnameRanksFirst() {
        assertTrue(ResearchSynthesis.containsAsWord("Update on (US) policy", "us"));
        assertFalse(ResearchSynthesis.containsAsWord("business", "US"));
        var official = new Evidence("Policy", "Official update", "https://data.example.gov/policy");
        var relevant = new Evidence("US policy", "New rule", "https://news.example/policy");
        var misleading = new Evidence("Business", "Unrelated trade", "https://business.example/example.gov");
        var sources = new java.util.ArrayList<>(List.of(misleading, relevant, official));
        IntStream.range(0, 9).mapToObj(i -> new Evidence("Unrelated", "Item " + i,
                "https://elsewhere.example/" + i, 1, 1)).forEach(sources::add);

        var filtered = ResearchSynthesis.filterCitationsByEntity(sources,
                new ResearchSynthesis.Entity("United States", List.of("US"), List.of("example.gov")));
        assertEquals(10, filtered.size());
        assertEquals(List.of(official, relevant), filtered.subList(0, 2));
        assertFalse(filtered.contains(misleading));
    }

    @Test void removesDuplicatesAndErrorLinesButKeepsEvidenceWithNumbers() {
        var original = new Evidence("First", "Revenue increased in the last quarter.\nUnable to retrieve\n未找到 2025 年数据", "");
        var duplicate = new Evidence("Second", original.text().toUpperCase(java.util.Locale.ROOT), "");
        var similar = new Evidence("Copy", "Revenue increased in the last quarter!\nUnable to retrieve\n未找到 2025 年数据", "");
        var failure = new Evidence("Error", "No results found", "");
        var clean = ResearchSynthesis.preprocessAgentResults(List.of(original, duplicate, similar, failure));

        assertEquals(1, clean.size());
        assertEquals("Revenue increased in the last quarter.\n未找到 2025 年数据", clean.getFirst().text());
    }

    @Test void completionCheckHandlesCjkAndOpenMarkdownFences() {
        assertTrue(ResearchSynthesis.looksComplete("证据仍不足。", 5));
        assertFalse(ResearchSynthesis.looksComplete("证据仍不足", 5));
        assertFalse(ResearchSynthesis.looksComplete("说明如下。\n```\nexample.", 5));
        assertFalse(ResearchSynthesis.looksComplete("完成。", 5));
    }
}
