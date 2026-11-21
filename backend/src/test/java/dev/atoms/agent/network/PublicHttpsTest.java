package dev.atoms.agent.network;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 验证受限地址和混合 DNS 记录会在连接前被拒绝。 */
class PublicHttpsTest {
    private static final URI IMAGE = URI.create("https://bucket.aliyuncs.com/image.png");

    @Test void rejectsPrivateAndSpecialUseAddressesIncludingMappedIp() throws Exception {
        for (String host : new String[]{"127.0.0.1", "0.0.0.0", "10.1.2.3", "100.100.100.200",
                "169.254.169.254", "172.16.0.2", "192.168.1.2", "168.63.129.16", "::1",
                "fd00:ec2::254", "::ffff:127.0.0.1", "2002:7f00:1::1", "2001:db8::1"}) {
            assertFalse(PublicHttps.isPublic(InetAddress.getByName(host)), host);
        }
        assertTrue(PublicHttps.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(PublicHttps.isPublic(InetAddress.getByName("2606:4700::1111")));
    }

    @Test void validatesEveryAnswerAndTheConnectionResolverNeverLooksUpAgain() throws Exception {
        var publicIp = InetAddress.getByName("8.8.8.8");
        var privateIp = InetAddress.getByName("10.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> PublicHttps.pin(IMAGE, h -> true,
                h -> new InetAddress[]{publicIp, privateIp}));
        var queries = new AtomicInteger();
        var pinned = PublicHttps.pin(IMAGE, h -> h.endsWith(".aliyuncs.com"),
                h -> new InetAddress[]{queries.incrementAndGet() == 1 ? publicIp : privateIp});
        assertEquals(publicIp, pinned.resolve("bucket.aliyuncs.com", 443).getFirst().getAddress());
        assertEquals(publicIp, pinned.resolve("bucket.aliyuncs.com")[0]);
        assertEquals(1, queries.get());
        assertThrows(UnknownHostException.class, () -> pinned.resolve("other.aliyuncs.com"));
    }

    @Test void rejectsUnexpectedSchemePortCredentialsOrDomainBeforeDns() {
        for (String url : new String[]{"http://bucket.aliyuncs.com", "https://bucket.aliyuncs.com:8443",
                "https://user@bucket.aliyuncs.com", "https://bucket.aliyuncs.com.evil.example", "https://127.0.0.1"}) {
            assertThrows(IllegalArgumentException.class, () -> PublicHttps.pin(URI.create(url),
                    host -> host.endsWith(".aliyuncs.com"), host -> { throw new AssertionError("不得查询 DNS"); }));
        }
    }
    @org.junit.jupiter.api.Test
    void legacySseMessageTargetRemainsOnThePinnedHttpsOrigin() throws Exception {
        var source = java.net.URI.create("https://docs.example.com/sse");
        org.junit.jupiter.api.Assertions.assertEquals("https://docs.example.com/messages?sessionId=known",
                PublicHttps.legacyTarget(source, "/messages?sessionId=known").toString());
        for (String invalid : java.util.List.of("//other.example.com/messages", "http://docs.example.com/messages",
                "https://docs.example.com:444/messages", "/messages#fragment")) {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> PublicHttps.legacyTarget(source, invalid));
        }
    }
}
