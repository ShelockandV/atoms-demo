package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class McpStdio {
    private final String image;
    private final ObjectMapper json;

    McpStdio(String image, ObjectMapper json) {
        if (!image.matches("[A-Za-z0-9][A-Za-z0-9./_:@-]{0,254}"))
            throw new IllegalArgumentException("MCP stdio 镜像无效");
        this.image = image;
        this.json = json;
    }

    JsonNode request(String method, Map<String, Object> params) throws IOException {
        String name = "atoms-mcp-" + UUID.randomUUID();
        var command = List.of("docker", "run", "--rm", "-i", "--pull=never", "--network=none", "--read-only",
                "--log-driver=none", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65532:65532",
                "--memory=256m", "--memory-swap=256m", "--pids-limit=64", "--cpus=1",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777", "--name", name, image);
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var worker = new FutureTask<>(() -> {
            try (var input = new BufferedInputStream(process.getInputStream()); var output = process.getOutputStream()) {
                int[] bytes = {0};
                return McpLegacySession.request(json, (message, id) -> {
                    output.write(message);
                    output.write('\n');
                    output.flush();
                    if (id == null) return null;
                    while (bytes[0] < 16_384) {
                        var line = new ByteArrayOutputStream();
                        for (int value; ; ) {
                            if (++bytes[0] > 16_384) throw new IOException("MCP stdio 响应过大");
                            value = input.read();
                            if (value == -1) throw new IOException("MCP stdio 提前结束");
                            if (value == '\n') break;
                            line.write(value);
                        }
                        if (line.size() == 0) continue;
                        byte[] data = line.toByteArray();
                        var rpc = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(data);
                        if (rpc == null || !"2.0".equals(rpc.path("jsonrpc").asText()))
                            throw new IOException("MCP stdio 返回无效消息");
                        if (!rpc.has("method") && id.equals(rpc.path("id").asText())) return data;
                        if (rpc.has("method") && rpc.hasNonNull("id"))
                            throw new IOException("MCP stdio 服务器请求需要当前不支持的交互");
                    }
                    throw new IOException("MCP stdio 响应过大");
                }, method, params);
            }
        });
        Thread.startVirtualThread(worker);
        boolean interrupted = false;
        try {
            return worker.get(25, TimeUnit.SECONDS);
        } catch (InterruptedException stop) {
            interrupted = true;
            throw new IOException("MCP stdio 已中断", stop);
        } catch (TimeoutException timeout) {
            throw new IOException("MCP stdio 超时", timeout);
        } catch (ExecutionException failure) {
            throw new IOException("MCP stdio 调用失败", failure.getCause());
        } finally {
            worker.cancel(true);
            process.destroyForcibly();
            try {
                var cleanup = new ProcessBuilder("docker", "rm", "-f", name)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!cleanup.waitFor(3, TimeUnit.SECONDS)) cleanup.destroyForcibly();
            } catch (IOException ignored) {
                // Docker may already have removed the container.
            } catch (InterruptedException stop) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
