package dev.atoms.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 在一次性容器中验收候选 Maven 项目或静态网页。
 * 例如 Java 源码编译失败时，将 Maven 诊断返回给 Agent 的修复循环。
 */
@Component
public class IsolatedAcceptance {
    private static final String BUILD = """
            set -eu
            mkdir /work/project
            cd /work/project
            cat > /work/source.zip
            jar xf /work/source.zip
            [ -d /opt/atoms-repository ] || exit 126
            cp -R /opt/atoms-repository /work/repository || exit 126
            exec mvn -B -o -ntp -Djansi.tmpdir=/work -Dmaven.repo.local=/work/repository test
            """;
    private static final String BROWSER = """
            set -eu
            mkdir /work/project
            cat > /work/source.zip
            unzip -q /work/source.zip -d /work/project
            exec node /opt/atoms/browser.cjs "$1"
            """;
    private final String mavenImage;
    private final String browserImage;

    public IsolatedAcceptance(@Value("${atoms.agent.acceptance.maven-image:}") String mavenImage,
                              @Value("${atoms.agent.acceptance.browser-image:}") String browserImage) {
        this.mavenImage = imageName(mavenImage);
        this.browserImage = imageName(browserImage);
    }

    private static String imageName(String configured) {
        String image = configured.trim();
        if (!image.isEmpty() && !image.matches("[a-zA-Z0-9][a-zA-Z0-9./_:@-]{0,254}"))
            throw new IllegalArgumentException("隔离验收镜像名称无效");
        return image;
    }

    public String verify(Map<String, String> files) throws Exception {
        var results = new ArrayList<String>();
        if (!mavenImage.isEmpty() && files.containsKey("pom.xml")) {
            run(SourceFiles.zip(files), mavenImage, BUILD, "Maven 编译或测试", "");
            results.add("隔离 Maven 编译与测试阶段通过。");
        }
        if (!browserImage.isEmpty() && SourceFiles.canPreview(files)) {
            String entry = files.containsKey("index.html") ? "index.html" : files.keySet().stream()
                    .filter(path -> path.endsWith("/index.html")).findFirst().orElseThrow();
            run(SourceFiles.zip(files), browserImage, BROWSER, "Chromium 页面冒烟检查", entry);
            results.add("隔离 Chromium 页面加载检查通过。");
        }
        return String.join(" ", results);
    }

    private void run(byte[] archive, String image, String script, String label, String entry) throws Exception {
        String name = "atoms-acceptance-" + UUID.randomUUID();
        var command = List.of("docker", "run", "--rm", "-i", "--pull=never", "--log-driver=none", "--name", name,
                "--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user=65532:65532", "--pids-limit=128", "--memory=1024m", "--memory-swap=1024m", "--cpus=1",
                "--tmpfs", "/work:rw,exec,nosuid,nodev,size=512m,mode=1777",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=32m,mode=1777",
                "--env", "HOME=/work", "--env", "MAVEN_CONFIG=/work/.m2", "--entrypoint", "/bin/sh",
                image, "-c", script, "atoms-acceptance", entry);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动隔离验收容器，请检查 Docker 配置", e);
        }
        var input = new FutureTask<Void>(() -> {
            try (var stream = process.getOutputStream()) { stream.write(archive); }
            return null;
        });
        var output = new FutureTask<String>(() -> {
            try (var stream = process.getInputStream()) {
                var tail = new byte[8192];
                var chunk = new byte[4096];
                int position = 0, size = 0;
                for (int n; (n = stream.read(chunk)) != -1;) {
                    for (int i = 0; i < n; i++) {
                        tail[position] = chunk[i];
                        position = (position + 1) % tail.length;
                    }
                    size = Math.min(tail.length, size + n);
                }
                var bytes = new byte[size];
                for (int i = 0; i < size; i++) bytes[i] = tail[(position - size + i + tail.length) % tail.length];
                return new String(bytes, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
            }
        });
        Thread.startVirtualThread(input);
        Thread.startVirtualThread(output);
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS))
                throw new IllegalStateException("隔离验收超过 90 秒");
            String diagnostics = output.get(3, TimeUnit.SECONDS);
            int status = process.exitValue();
            if (status == 125 || status == 126 || status == 127)
                throw new IllegalStateException("隔离验收环境不可用，请检查 Docker 和预装镜像：" + diagnostics);
            if (status != 0) {
                int start = Math.max(0, diagnostics.length() - 1600);
                throw new IllegalArgumentException("隔离 " + label + "失败（退出码 " + status + "）：\n"
                        + diagnostics.substring(start));
            }
            input.get(3, TimeUnit.SECONDS);
        } finally {
            input.cancel(true);
            output.cancel(true);
            if (process.isAlive()) {
                process.destroyForcibly();
                removeContainer(name);
            }
        }
    }

    private static void removeContainer(String name) {
        try {
            var cleanup = new ProcessBuilder("docker", "rm", "-f", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The Docker daemon may already have removed the container.
        }
    }
}
