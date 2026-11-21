package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderContentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void claudeStreamPreservesSignedAndRedactedThinkingForReplay() throws Exception {
        String events = """
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"summary"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque"}}

                data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"encrypted"}}

                data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":"visible answer"}}

                data: {"type":"message_stop"}

                """;
        var visible = new StringBuilder();
        var streamed = NativeModelStream.claude(json, new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)),
                5, visible::append).message();
        assertEquals("visible answer", visible.toString());
        assertEquals("opaque", streamed.at("/provider_content/blocks/0/signature").asText());
        assertEquals("encrypted", streamed.at("/provider_content/blocks/1/data").asText());
        var replay = AnthropicMessages.request(json, List.of(Map.of("role", "assistant", "content", "visible answer",
                "provider_content", streamed.get("provider_content").toString())), Map.of());
        assertEquals(streamed.at("/provider_content/blocks"), json.valueToTree(replay).at("/messages/0/content"));
    }

    @Test void geminiThoughtsRemainSeparateAndKeepTheirSignaturesOnReplay() throws Exception {
        var candidate = json.readTree("""
                {"content":{"parts":[{"text":"summary","thought":true,"thoughtSignature":"opaque"},
                  {"text":"visible answer"}]}}
                """);
        var answer = GeminiContent.message(json, candidate);
        assertEquals("visible answer", answer.path("content").asText());
        var replay = GeminiContent.request(json, List.of(Map.of("role", "assistant", "content", "visible answer",
                "provider_content", answer.get("provider_content").toString())), Map.of(), 100);
        assertEquals(candidate.at("/content/parts"), json.valueToTree(replay).at("/contents/0/parts"));
    }
}
