package dev.atoms.agent.network;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;

import java.io.IOException;
import java.net.*;
import java.util.Locale;
import java.util.function.Predicate;

/** 受控的公网 HTTPS 读取器。例如下载供应商返回的图片时，连接只使用检查过的 DNS 地址。 */
public final class PublicHttps {
    /** DNS 查询接口，可在测试中替换为固定的解析结果。 */
    @FunctionalInterface
    interface Lookup { InetAddress[] resolve(String host) throws UnknownHostException; }

    /** 返回已限制大小的响应体及其媒体类型。 */
    public record Download(byte[] bytes, String mediaType) {}

    private PublicHttps() {}

    public static Download get(URI uri, Predicate<String> allowedHost, int maxBytes) throws IOException {
        return exchange(new HttpGet(uri), allowedHost, maxBytes, false);
    }

    public static Download callMcpTool(URI uri, Predicate<String> allowedHost, byte[] payload,
                                       String tool, int maxBytes) throws IOException {
        if (payload.length > 32_768 || !tool.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("MCP 请求参数超过限制");
        var request = new HttpPost(uri);
        request.setHeader("Accept", "application/json, text/event-stream");
        request.setHeader("MCP-Protocol-Version", "2026-07-28");
        request.setHeader("Mcp-Method", "tools/call");
        request.setHeader("Mcp-Name", tool);
        request.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_JSON));
        return exchange(request, allowedHost, maxBytes, true);
    }

    private static Download exchange(HttpUriRequestBase request, Predicate<String> allowedHost, int maxBytes, boolean jsonOnly)
            throws IOException {
        if (maxBytes < 1 || maxBytes > 8_000_000) throw new IllegalArgumentException("读取大小上限无效");
        URI uri;
        try { uri = request.getUri(); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("公网请求地址无效", e); }
        var dns = pin(uri, allowedHost, InetAddress::getAllByName);
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10)).setSocketTimeout(Timeout.ofSeconds(20)).build())
                .build();
        try (var client = HttpClients.custom().setConnectionManager(connections)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().build()) {
            return client.execute(request, response -> {
                if (response.getCode() != 200 || response.getEntity() == null) {
                    request.cancel();
                    throw new IOException("公网下载未返回可用响应（HTTP " + response.getCode() + "）");
                }
                var entity = response.getEntity();
                String contentType = entity.getContentType();
                String type = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                if (jsonOnly && !"application/json".equals(type)) {
                    request.cancel();
                    throw new IllegalArgumentException("当前 MCP 工具仅支持单条 JSON 响应");
                }
                try (var body = entity.getContent()) {
                    var bytes = body.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) {
                        request.cancel();
                        throw new IOException("公网下载超过大小上限");
                    }
                    return new Download(bytes, type);
                }
            });
        }
    }

    static DnsResolver pin(URI uri, Predicate<String> allowedHost, Lookup lookup) throws UnknownHostException {
        String host = validateEndpoint(uri, allowedHost);
        var addresses = lookup.resolve(host).clone();
        if (addresses.length == 0) throw new UnknownHostException("公网下载域名没有地址");
        for (var address : addresses)
            if (!isPublic(address)) throw new IllegalArgumentException("公网下载域名解析到了受限地址");
        return new DnsResolver() {
            @Override public InetAddress[] resolve(String requested) throws UnknownHostException {
                if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("连接域名与预检域名不一致");
                return addresses.clone();
            }

            @Override public String resolveCanonicalHostname(String requested) throws UnknownHostException {
                if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("连接域名与预检域名不一致");
                return host;
            }
        };
    }

    public static String validateEndpoint(URI uri, Predicate<String> allowedHost) {
        String rawHost = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || rawHost == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("公网下载仅允许无凭证的 HTTPS 标准端口地址");
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (!host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                || host.matches("[0-9.]+") || !allowedHost.test(host))
            throw new IllegalArgumentException("公网下载域名不在允许范围内");
        return host;
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]), c = Byte.toUnsignedInt(bytes[2]);
        if (bytes.length == 4) {
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 168 && b == 63 && c == 129 && Byte.toUnsignedInt(bytes[3]) == 16)
                    && !(a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
                    && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    && !(a == 203 && b == 0 && c == 113);
        }
        // Permit global unicast only, with special-purpose and transition allocations excluded.
        return (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 1 && (c < 2 || c == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8))
                && !(a == 0x20 && b == 2)
                && !(a == 0x3f && (b & 0xf0) == 0xf0);
    }
}
