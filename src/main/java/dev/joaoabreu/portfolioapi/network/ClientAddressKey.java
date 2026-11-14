package dev.joaoabreu.portfolioapi.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class ClientAddressKey {
    private final List<IpAddressMatcher> proxies;
    private final List<IpAddressMatcher> cloudflare;

    public ClientAddressKey(@Value("${portfolio.client-address.trusted-proxies:}") List<String> proxies,
            @Value("${portfolio.client-address.cloudflare-cidrs:}") List<String> cloudflare) {
        this.proxies = ranges(proxies);
        this.cloudflare = ranges(cloudflare);
        if (this.proxies.isEmpty() != this.cloudflare.isEmpty())
            throw new IllegalArgumentException("Configure both trusted proxies and Cloudflare ranges, or neither");
    }

    public static ClientAddressKey direct() { return new ClientAddressKey(List.of(), List.of()); }

    public String of(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (proxies.isEmpty()) return peer;
        String edge = canonical(request.getHeader("X-Real-IP"));
        String client = canonical(request.getHeader("CF-Connecting-IP"));
        String socket = canonical(peer);
        if (socket == null || edge == null || client == null || !matches(proxies, socket) || !matches(cloudflare, edge))
            return peer;
        return client;
    }

    private static List<IpAddressMatcher> ranges(List<String> values) {
        return values.stream().map(String::strip).filter(value -> !value.isEmpty()).map(value -> {
            if (canonical(value.split("/", -1)[0]) == null) throw new IllegalArgumentException("Invalid CIDR: " + value);
            return new IpAddressMatcher(value);
        }).toList();
    }

    private static boolean matches(List<IpAddressMatcher> ranges, String address) {
        return ranges.stream().anyMatch(range -> range.matches(address));
    }

    static String canonical(String value) {
        if (value == null || value.isBlank() || value.length() > 45) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
        } else if (!value.matches("(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
