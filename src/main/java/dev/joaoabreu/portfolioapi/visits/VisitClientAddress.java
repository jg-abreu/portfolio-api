package dev.joaoabreu.portfolioapi.visits;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "portfolio.visits.enabled", havingValue = "true")
public class VisitClientAddress {
    private final VisitConfiguration.Properties properties;
    private final List<IpAddressMatcher> proxies;
    private final List<IpAddressMatcher> cloudflare;

    public VisitClientAddress(VisitConfiguration.Properties properties) {
        this.properties = properties;
        this.proxies = ranges(properties.trustedProxies());
        this.cloudflare = ranges(properties.cloudflareCidrs());
    }

    public byte[] resolve(HttpServletRequest request) {
        byte[] peer = literal(request.getRemoteAddr());
        if (properties.ipSource() == VisitConfiguration.IpSource.DIRECT) return peer;
        if (!matches(proxies, peer) || !matches(cloudflare, literal(request.getHeader("X-Real-IP"))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return literal(request.getHeader("CF-Connecting-IP"));
    }

    private static List<IpAddressMatcher> ranges(List<String> values) {
        return values.stream().map(value -> {
            literal(value.split("/", -1)[0]);
            return new IpAddressMatcher(value);
        }).toList();
    }

    private static boolean matches(List<IpAddressMatcher> ranges, byte[] bytes) {
        try {
            String canonical = InetAddress.getByAddress(bytes).getHostAddress();
            return ranges.stream().anyMatch(range -> range.matches(canonical));
        } catch (UnknownHostException exception) { throw new IllegalStateException("Invalid address bytes"); }
    }

    public static byte[] literal(String value) {
        if (value == null || value.isBlank() || value.length() > 45)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            try { return InetAddress.getByName(value).getAddress(); }
            catch (UnknownHostException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        byte[] result = new byte[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("[0-9]{1,3}") || Integer.parseInt(parts[index]) > 255)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            result[index] = (byte) Integer.parseInt(parts[index]);
        }
        return result;
    }
}
