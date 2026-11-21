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
import java.io.InputStream;
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

    @FunctionalInterface
    public interface EventStreamResponse {
        byte[] read(InputStream body, int maxBytes) throws IOException;
    }

    private PublicHttps() {}

    public static Download get(URI uri, Predicate<String> allowedHost, int maxBytes) throws IOException {
        return exchange(new HttpGet(uri), allowedHost, maxBytes, null);
    }

    /** Resolve provider image downloads through a dedicated DNS server when local DNS returns fake IPs. */
    public static Download getProviderImage(URI uri, int maxBytes, String dnsServer) throws IOException {
        return exchange(new HttpGet(uri), host -> host.endsWith(".aliyuncs.com"), maxBytes, null, host -> {
            var local = InetAddress.getAllByName(host);
            return !dnsServer.isBlank() && local.length > 0 && java.util.Arrays.stream(local).allMatch(PublicHttps::isFakeIp)
                    ? resolveProviderDns(host, dnsServer) : local;
        });
    }

    private static boolean isFakeIp(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && Byte.toUnsignedInt(bytes[0]) == 198 && (bytes[1] == 18 || bytes[1] == 19);
    }

    private static InetAddress[] resolveProviderDns(String host, String server) throws UnknownHostException {
        if (!server.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || !isPublic(InetAddress.getByName(server)))
            throw new IllegalArgumentException("图片下载备用 DNS 必须是公网 IPv4 地址");
        var settings = new java.util.Hashtable<String, String>();
        settings.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        settings.put(javax.naming.Context.PROVIDER_URL, "dns://" + server + "/");
        settings.put("com.sun.jndi.dns.timeout.initial", "2000");
        settings.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            var dns = new javax.naming.directory.InitialDirContext(settings);
            try {
                var records = dns.getAttributes(host + ".", new String[]{"A"});
                var addresses = new java.util.ArrayList<InetAddress>();
                var record = records.get("A");
                if (record != null) {
                    var values = record.getAll();
                    while (values.hasMore()) {
                        String address = values.next().toString();
                        if (!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new UnknownHostException(host);
                        addresses.add(InetAddress.getByName(address));
                    }
                }
                return addresses.toArray(InetAddress[]::new);
            } finally {
                dns.close();
            }
        } catch (javax.naming.NamingException error) {
            var failure = new UnknownHostException("图片下载备用 DNS 查询失败");
            failure.initCause(error);
            throw failure;
        }
    }

    public static Download callMcpTool(URI uri, Predicate<String> allowedHost, byte[] payload,
                                       String tool, int maxBytes, EventStreamResponse sse) throws IOException {
        return callMcp(uri, allowedHost, payload, "tools/call", tool, "", maxBytes, sse);
    }

    public static Download callMcp(URI uri, Predicate<String> allowedHost, byte[] payload,
                                   String method, String tool, String token, int maxBytes, EventStreamResponse sse) throws IOException {
        if (payload.length > 32_768 || !tool.matches("[A-Za-z0-9_.-]{0,128}")
                || (!"tools/call".equals(method) && !"tools/list".equals(method)))
            throw new IllegalArgumentException("MCP 请求参数超过限制");
        var request = new HttpPost(uri);
        request.setHeader("Accept", "application/json, text/event-stream");
        request.setHeader("MCP-Protocol-Version", "2026-07-28");
        request.setHeader("Mcp-Method", method);
        if (!tool.isEmpty()) request.setHeader("Mcp-Name", tool);
        authorize(request, token);
        request.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_JSON));
        return exchange(request, allowedHost, maxBytes, java.util.Objects.requireNonNull(sse));
    }

    public static Download oauthToken(URI uri, String form, String authorization) throws IOException {
        var request = new HttpPost(uri);
        request.setHeader("Accept", "application/json");
        request.setHeader("Authorization", authorization);
        request.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(form, ContentType.APPLICATION_FORM_URLENCODED));
        return exchange(request, uri.getHost()::equalsIgnoreCase, 16_384, null);
    }

    @FunctionalInterface
    public interface LegacyConversation<T> {
        T exchange(InputStream stream, LegacyPost post) throws IOException;
    }

    @FunctionalInterface
    public interface LegacyPost {
        void send(String path, byte[] message) throws IOException;
    }

    public static <T> T legacyMcp(URI uri, String token, LegacyConversation<T> conversation) throws IOException {
        var request = new HttpGet(uri);
        request.setHeader("Accept", "text/event-stream");
        authorize(request, token);
        try (var client = client(uri, uri.getHost()::equalsIgnoreCase)) {
            return client.execute(request, response -> {
                var entity = response.getEntity();
                if (response.getCode() != 200 || entity == null || !"text/event-stream".equals(mediaType(entity.getContentType()))) {
                    request.cancel();
                    throw new IOException("旧版 MCP 端点未建立 SSE 连接");
                }
                try (var stream = entity.getContent()) {
                    try {
                        return conversation.exchange(stream, (path, message) -> {
                            URI target = legacyTarget(uri, path);
                            if (message.length > 32_768) throw new IOException("MCP 请求过大");
                            var post = new HttpPost(target);
                            authorize(post, token);
                            post.setEntity(new ByteArrayEntity(message, ContentType.APPLICATION_JSON));
                            client.execute(post, reply -> {
                                int code = reply.getCode();
                                post.cancel();
                                if (code < 200 || code >= 300) throw new IOException("旧版 MCP 消息发送失败");
                                return null;
                            });
                        });
                    } finally {
                        request.cancel();
                    }
                }
            });
        }
    }

    static URI legacyTarget(URI source, String path) throws IOException {
        try {
            URI target = source.resolve(path);
            validateEndpoint(target, source.getHost()::equalsIgnoreCase);
            if (path.length() > 2048 || target.getRawPath() == null || target.getRawPath().isEmpty())
                throw new IOException("旧版 MCP 消息端点无效");
            return target;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("旧版 MCP 消息端点须与 SSE 端点同源", invalid);
        }
    }

    private static void authorize(HttpUriRequestBase request, String token) {
        if (!token.isEmpty()) request.setHeader("Authorization", "Bearer " + token);
    }

    private static String mediaType(String contentType) {
        return contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private static org.apache.hc.client5.http.impl.classic.CloseableHttpClient client(URI uri, Predicate<String> allowedHost)
            throws IOException {
        return client(uri, allowedHost, InetAddress::getAllByName);
    }

    private static org.apache.hc.client5.http.impl.classic.CloseableHttpClient client(
            URI uri, Predicate<String> allowedHost, Lookup lookup) throws IOException {
        var dns = pin(uri, allowedHost, lookup);
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10)).setSocketTimeout(Timeout.ofSeconds(20)).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression().build();
    }

    private static Download exchange(HttpUriRequestBase request, Predicate<String> allowedHost, int maxBytes, EventStreamResponse sse)
            throws IOException {
        return exchange(request, allowedHost, maxBytes, sse, InetAddress::getAllByName);
    }

    private static Download exchange(HttpUriRequestBase request, Predicate<String> allowedHost, int maxBytes,
                                     EventStreamResponse sse, Lookup lookup) throws IOException {
        if (maxBytes < 1 || maxBytes > 8_000_000) throw new IllegalArgumentException("读取大小上限无效");
        URI uri;
        try { uri = request.getUri(); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("公网请求地址无效", e); }
        try (var client = client(uri, allowedHost, lookup)) {
            return client.execute(request, response -> {
                if (response.getCode() != 200 || response.getEntity() == null) {
                    request.cancel();
                    throw new IOException("公网下载未返回可用响应（HTTP " + response.getCode() + "）");
                }
                var entity = response.getEntity();
                String type = mediaType(entity.getContentType());
                if (sse != null && "text/event-stream".equals(type)) {
                    try (var body = entity.getContent()) {
                        try {
                            return new Download(sse.read(body, maxBytes), "application/json");
                        } finally {
                            request.cancel();
                        }
                    }
                }
                if (sse != null && !"application/json".equals(type)) {
                    request.cancel();
                    throw new IllegalArgumentException("MCP 响应须为 JSON 或 SSE");
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
        for (var address : addresses) {
            if (isPublic(address)) continue;
            if (isFakeIp(address))
                throw new IllegalArgumentException("公网下载域名解析到了 198.18.0.0/15 虚拟地址，请让代理对该域名返回真实公网 DNS 地址后重试");
            throw new IllegalArgumentException("公网下载域名解析到了受限地址");
        }
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
