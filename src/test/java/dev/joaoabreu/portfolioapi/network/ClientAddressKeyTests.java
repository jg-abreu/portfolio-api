package dev.joaoabreu.portfolioapi.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientAddressKeyTests {
    final ClientAddressKey cloudflare = new ClientAddressKey(List.of("172.18.0.1/32"), List.of("173.245.48.0/20", "2400:cb00::/32"));

    MockHttpServletRequest request(String peer, String realIp, String connectingIp) {
        var request = new MockHttpServletRequest("POST", "/api/assistant/messages");
        request.setRemoteAddr(peer);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        if (connectingIp != null) request.addHeader("CF-Connecting-IP", connectingIp);
        return request;
    }

    @Test
    void trustedChainUsesTheVisitorAddressFromCloudflare() {
        assertEquals("198.51.100.7", cloudflare.of(request("172.18.0.1", "173.245.48.10", "198.51.100.7")));
        assertEquals("2001:db8:0:0:0:0:0:5", cloudflare.of(request("172.18.0.1", "2400:cb00::1", "2001:db8::5")));
    }

    @Test
    void untrustedPeerOrEdgeIgnoresForwardedHeaders() {
        assertEquals("203.0.113.9", cloudflare.of(request("203.0.113.9", "173.245.48.10", "198.51.100.7")));
        assertEquals("172.18.0.1", cloudflare.of(request("172.18.0.1", "203.0.113.50", "198.51.100.7")));
    }

    @Test
    void missingOrMalformedHeadersFallBackToThePeer() {
        assertEquals("172.18.0.1", cloudflare.of(request("172.18.0.1", null, "198.51.100.7")));
        assertEquals("172.18.0.1", cloudflare.of(request("172.18.0.1", "173.245.48.10", null)));
        for (String invalid : List.of("198.51.100.7, 10.0.0.1", "example.com", "999.1.1.1", "fe80::1%eth0", "1.2.3"))
            assertEquals("172.18.0.1", cloudflare.of(request("172.18.0.1", "173.245.48.10", invalid)));
    }

    @Test
    void directModeAlwaysUsesThePeer() {
        assertEquals("172.18.0.1", ClientAddressKey.direct().of(request("172.18.0.1", "173.245.48.10", "198.51.100.7")));
    }

    @Test
    void configurationMustBeCompleteAndValid() {
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressKey(List.of("172.18.0.1/32"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClientAddressKey(List.of("not-an-ip/8"), List.of("173.245.48.0/20")));
    }
}
