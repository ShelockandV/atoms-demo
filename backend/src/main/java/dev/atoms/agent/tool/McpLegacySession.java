package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class McpLegacySession {
    static final Map<String, String> CLIENT = Map.of("name", "atoms", "version", "0.1.0");
    private static final String VERSION = "2024-11-05";

    @FunctionalInterface
    interface Channel {
        byte[] request(byte[] message, String id) throws IOException;
    }

    static JsonNode request(ObjectMapper json, Channel channel, String method, Map<String, Object> params) throws IOException {
        var init = rpc(json, channel, "initialize", Map.of("protocolVersion", VERSION,
                "clientInfo", CLIENT, "capabilities", Map.of()));
        if (!Set.of(VERSION, "2025-03-26", "2025-06-18", "2025-11-25").contains(init.path("protocolVersion").asText()))
            throw new IOException("旧版 MCP 协议版本不受支持");
        channel.request(json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")), null);
        return rpc(json, channel, method, params);
    }

    private static JsonNode rpc(ObjectMapper json, Channel channel, String method, Map<String, Object> params) throws IOException {
        String id = UUID.randomUUID().toString();
        byte[] response = channel.request(json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "method", method,
                "id", id, "params", params)), id);
        return result(json, response, id);
    }

    static JsonNode result(ObjectMapper json, byte[] response, String id) throws IOException {
        var node = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response);
        if (node == null || !"2.0".equals(node.path("jsonrpc").asText()) || !node.path("id").isTextual()
                || !id.equals(node.path("id").asText()) || node.has("error") || !node.path("result").isObject())
            throw new IOException("MCP 返回协议错误或响应 ID 不匹配");
        return node.get("result");
    }
}
