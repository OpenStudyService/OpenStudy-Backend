package com.openstudy.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Resolves the client address used as a rate-limit key.
 * X-Forwarded-For is read only when the direct peer is a configured trusted proxy, and then from the right:
 * the first hop that is not itself trusted is the client. Hops a client prepends cannot move that position.
 * Requires server.forward-headers-strategy to stay NONE so the servlet remote address is the real TCP peer.
 * The result is an in-memory key only; it is never logged or stored.
 */
@Component
public class ClientAddressResolver {
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");
    private static final String UNKNOWN = "unknown";
    private final List<IpAddressMatcher> trustedProxies;

    public ClientAddressResolver(@Value("${openstudy.web.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> {
                    String address = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
                    if (literal(address) == null) {
                        throw new IllegalArgumentException("openstudy.web.trusted-proxies accepts IP addresses or CIDR ranges only");
                    }
                    return new IpAddressMatcher(value);
                })
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null) return UNKNOWN;
        if (!trusted(peer)) return peer;
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            hops.addAll(Arrays.asList(header.split(",")));
        }
        String client = peer;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = literal(hops.get(i));
            if (hop == null) break;
            client = hop;
            if (!trusted(hop)) break;
        }
        return client;
    }

    private boolean trusted(String address) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    /** Accepts IP literals only, so a header value can never trigger a DNS lookup. */
    private static String literal(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) candidate = candidate.substring(1, candidate.length() - 1);
        boolean ipv4 = IPV4.matcher(candidate).matches();
        boolean ipv6 = candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches();
        if (!ipv4 && !ipv6) return null;
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
