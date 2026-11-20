package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.net.URIBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证公网工具只能用声明的查询字段访问配置中的固定端点。 */
class HttpToolFactoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String CONFIG = """
            [{"name":"HTTP_DOCS","description":"查询公开文档","url":"https://docs.example.com/search",
              "query":[{"name":"q","description":"查询词","required":true}]}]
            """;

    @Test void registersSchemaAndKeepsDestinationFixedWhenEncodingArguments() throws Exception {
        var registry = new ToolRegistry<>(HttpToolFactory.load(json, CONFIG.getBytes(StandardCharsets.UTF_8)));
        var tool = (HttpToolFactory.ReadTool<?>) registry.require("HTTP_DOCS");
        String query = "a&url=https://another.example/path#fragment";
        registry.validate(registry.require("HTTP_DOCS"), Map.of("q", query), json);
        var uri = tool.requestUri(Map.of("q", query));
        assertEquals("docs.example.com", uri.getHost());
        assertEquals("/search", uri.getPath());
        var params = new URIBuilder(uri).getQueryParams();
        assertEquals(1, params.size());
        assertEquals(query, params.getFirst().getValue());
        assertThrows(IllegalArgumentException.class,
                () -> registry.validate(registry.require("HTTP_DOCS"), Map.of("q", "hi", "url", "other"), json));
    }

    @Test void rejectsNonHttpsEndpointsAndUnsupportedCredentialFieldsAtStartup() {
        for (String invalid : new String[]{
                CONFIG.replace("https://", "http://"),
                CONFIG.replace("https://docs.example.com", "https://127.0.0.1"),
                CONFIG.replace("\"query\":", "\"headers\":{},\"query\":")
        }) {
            assertThrows(IllegalArgumentException.class, () -> HttpToolFactory.load(json,
                    invalid.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void importsOnlySelectedOpenApiReadsAndUsesOperatorConfiguredAddress() throws Exception {
        String manifest = """
                {"baseUrl":"https://docs.example.com/v1","operations":{"search":"HTTP_OPENAPI_DOCS"},
                 "spec":{"openapi":"3.1.0","servers":[{"url":"https://ignored.example.com"}],
                    "paths":{"/docs":{"get":{"operationId":"search","summary":"Search",
                      "parameters":[{"name":"q","in":"query","required":true,"schema":{"type":"string"}}]}},
                      "/write":{"post":{"operationId":"write"}}}}}
                """;
        var tools = OpenApiToolImport.load(json, manifest.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, tools.size());
        var read = (HttpToolFactory.ReadTool<?>) tools.getFirst();
        assertEquals("HTTP_OPENAPI_DOCS", read.metadata().name());
        assertEquals("https://docs.example.com/v1/docs?q=java", read.requestUri(Map.of("q", "java")).toString());
        for (var invalid : new String[]{manifest.replace("\"search\":\"HTTP_OPENAPI_DOCS\"", "\"write\":\"HTTP_OPENAPI_DOCS\""),
                manifest.replace("\"operationId\":\"search\"", "\"operationId\":\"search\",\"security\":[{\"key\":[]}]"),
                manifest.replace("\"/docs\"", "\"/docs/{id}\"")}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OpenApiToolImport.load(json, invalid.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
