package dev.atoms.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "atoms.test.joint-container", matches = "true")
class JointAcceptanceContainerTest {
    private static final Map<String, String> PROJECT = Map.of(
            "pom.xml", """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.7</version>
                        <relativePath/>
                      </parent>
                      <groupId>dev.atoms.fixture</groupId>
                      <artifactId>joint-fixture</artifactId>
                      <version>1.0.0</version>
                      <properties><java.version>21</java.version></properties>
                      <dependencies>
                        <dependency>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-test</artifactId>
                          <scope>test</scope>
                        </dependency>
                      </dependencies>
                      <build><plugins><plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                      </plugin></plugins></build>
                    </project>
                    """,
            "src/main/java/fixture/App.java", """
                    package fixture;

                    import org.springframework.boot.SpringApplication;
                    import org.springframework.boot.autoconfigure.SpringBootApplication;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    @SpringBootApplication
                    @RestController
                    public class App {
                        public static void main(String[] args) { SpringApplication.run(App.class, args); }

                        @GetMapping("/api/health")
                        String health() { return "ok"; }

                        @GetMapping("/api/tasks")
                        Task tasks() { return new Task("task-1", "待办任务"); }

                        record Task(String id, String title) {}
                    }
                    """,
            "src/test/java/fixture/AppTest.java", """
                    package fixture;

                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;

                    class AppTest {
                        @Test void taskHasStableId() { assertEquals("task-1", new App().tasks().id()); }
                    }
                    """,
            "src/main/resources/static/index.html", """
                    <!doctype html>
                    <html lang="zh-CN">
                    <meta charset="utf-8">
                    <title>联合验收</title>
                    <button type="button">加载任务</button>
                    <output></output>
                    <script>
                      document.querySelector("button").addEventListener("click", async () => {
                        const response = await fetch("/api/tasks");
                        if (!response.ok) throw new Error("加载任务失败");
                        document.querySelector("output").textContent = (await response.json()).title;
                      });
                    </script>
                    </html>
                    """);

    @Test
    void buildsStartsAndVerifiesBrowserInteraction() throws Exception {
        assertEquals("隔离编译与测试、Spring Boot 启动和浏览器联合交互检查通过。",
                acceptance("task-1").verify(PROJECT));
    }

    @Test
    void reportsIncorrectApiResponseAsCandidateFailure() {
        var error = assertThrows(IllegalArgumentException.class, () -> acceptance("task-2").verify(PROJECT));
        assertTrue(error.getMessage().contains("后端响应缺少预期内容"), error::getMessage);
    }

    private static IsolatedAcceptance acceptance(String expectedId) {
        String scenario = """
                {"page":"/","click":"加载任务","endpoint":"/api/tasks","method":"GET",
                 "responseText":"%s","visibleText":"待办任务"}
                """.formatted(expectedId);
        return new IsolatedAcceptance("", "", "/api/health", "atoms-acceptance-joint:local", scenario);
    }
}
