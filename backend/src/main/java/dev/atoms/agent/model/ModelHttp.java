package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.UUID;

/**
 * 为模型客户端提供地址校验和供应商错误信息转换。
 *
 * <p>示例：<code>ModelHttp.endpoint("https://example.com/v1")</code>。</p>
 */
public final class ModelHttp {
    private ModelHttp() {}

    static void addTraceparent(HttpRequest.Builder request, String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32))) return;
        String span = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.header("traceparent", "00-" + traceId + "-" + span + "-00");
    }

    public static URI endpoint(String base) {
        String hint = "模型地址配置错误：请在 后端私有配置中的 atoms.model.base-url 中填写完整的 http:// 或 https:// 地址及供应商 API 路径前缀";
        if (base == null || base.isBlank()) throw new IllegalArgumentException(hint);
        try {
            URI uri = URI.create(base.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(hint);
            }
            String normalized = uri.toString().replaceAll("/+$", "");
            return URI.create(normalized.endsWith("/chat/completions") ? normalized : normalized + "/chat/completions");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(hint);
        }
    }

    public static String providerError(ObjectMapper json, int status, String body) {
        // Display known provider codes without exposing raw responses or credentials.
        String code = "";
        if (body.length() <= 100_000) {
            try {
                var root = json.readTree(body);
                code = root.path("error").path("code").asText("");
                if (code.isBlank()) code = root.path("code").asText("");
            } catch (Exception ignored) {
            }
        }
        if ("AccessDenied.Unpurchased".equals(code)) {
            return "模型访问被拒绝（HTTP " + status + "，AccessDenied.Unpurchased）：供应商提示服务尚未开通，请使用阿里云主账号检查当前地域的百炼服务开通状态；若已开通，再核对 API Key 所属工作空间和模型授权";
        }
        return switch (status) {
            case 401 -> "模型认证失败（HTTP 401）：请检查 API Key 是否有效，以及是否属于当前地域与工作空间";
            case 403 -> "模型访问被拒绝（HTTP 403）：请检查账号及工作空间的模型授权与服务开通状态";
            case 404 -> "模型接口或模型不存在（HTTP 404）：请检查 API 路径前缀和模型 ID";
            case 429 -> "模型请求受限（HTTP 429）：请检查调用频率、额度与并发限制，稍后再试";
            default -> "模型服务返回 HTTP " + status + "，请检查供应商服务状态及配置";
        };
    }
}
