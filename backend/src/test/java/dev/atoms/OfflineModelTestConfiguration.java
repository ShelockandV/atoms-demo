package dev.atoms;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.model.ImageClient;
import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.tool.ToolRegistry;
import dev.atoms.agent.tool.builtin.ToolContext;
import dev.atoms.workspace.ProjectContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
public class OfflineModelTestConfiguration {
    @Bean
    @Primary
    ModelClient offlineTextModel(ObjectMapper json, ToolRegistry<ToolContext> tools) {
        return new ModelClient(json, true, "http://unused", "", "test", 5, 16000, tools) {
            @Override
            public Map<String, String> generate(String prompt, String plan, ProjectContext context,
                                                String failure, String selectedModel) {
                var files = new LinkedHashMap<>(context.files());
                files.merge("index.html", "<!doctype html><html><body><main>Test artifact</main></body></html>",
                        (existing, ignored) -> existing + "\n<!-- Test artifact updated -->");
                return files;
            }
        };
    }

    @Bean
    @Primary
    ImageClient offlineImageModel(ObjectMapper json) {
        return new ImageClient(json, "", "", "", "", "1024*1024") {
            @Override
            public Image generate(String prompt, String selectedModel, String traceId) {
                return new Image(Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jH9sAAAAASUVORK5CYII="),
                        "image/png");
            }

            @Override
            public Image edit(String prompt, String selectedModel, String traceId, Image source) {
                return source;
            }
        };
    }
}
