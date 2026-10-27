package com.openstudy.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressResolverTests {
    @Test
    void withoutTrustedProxiesForwardedHeadersAreIgnored() {
        var resolver = new ClientAddressResolver("");
        assertThat(resolver.resolve(request("203.0.113.7", "198.51.100.1"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request("203.0.113.7", "198.51.100.2, 10.0.0.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    void untrustedPeerCannotSpoofEvenWhenTrustedProxiesAreConfigured() {
        var resolver = new ClientAddressResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("203.0.113.7", "10.0.0.5"))).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedPeerUsesTheRightmostUntrustedHop() {
        var resolver = new ClientAddressResolver("10.0.0.0/8, 172.16.0.10");
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.9"))).isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(request("10.0.0.2", "1.1.1.1, 198.51.100.9"))).isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(request("10.0.0.2", "1.1.1.1, 198.51.100.9, 172.16.0.10"))).isEqualTo("198.51.100.9");
        assertThat(resolver.resolve(request("10.0.0.2", "1.1.1.1", "198.51.100.9"))).isEqualTo("198.51.100.9");
    }

    @Test
    void malformedOrHostnameHopsStopAtTheLastTrustedAddress() {
        var resolver = new ClientAddressResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.2", "evil.example.com"))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.9, garbage"))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.9:443"))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2"))).isEqualTo("10.0.0.2");
    }

    @Test
    void normalizesIpv6AndMappedAddresses() {
        var resolver = new ClientAddressResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.2", "[2001:DB8:0:0:0:0:0:1]"))).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.resolve(request("::ffff:10.0.0.2", "198.51.100.9"))).isEqualTo("198.51.100.9");
    }

    @Test
    void rejectsHostnamesInConfiguration() {
        assertThatThrownBy(() -> new ClientAddressResolver("proxy.internal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddressResolver("10.0.0.0/8, nginx")).isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletRequest request(String peer, String... forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String value : forwarded) request.addHeader("X-Forwarded-For", value);
        return request;
    }
}
