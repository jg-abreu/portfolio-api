package dev.joaoabreu.portfolioapi.contact;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import dev.joaoabreu.portfolioapi.network.ClientAddressKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "portfolio.contact.submission.enabled", havingValue = "true")
public class ContactSubmissionFilter extends OncePerRequestFilter {
    private final Map<String, Integer> clients = new HashMap<>();
    private Instant windowEnd = Instant.EPOCH;
    private int total;
    private final ClientAddressKey addresses;

    public ContactSubmissionFilter() { this(ClientAddressKey.direct()); }

    @Autowired
    public ContactSubmissionFilter(ClientAddressKey addresses) { this.addresses = addresses; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getRequestURI().equals("/api/contact");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())
                    || request.getHeader("Content-Encoding") != null
                    && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity")) {
                response.setStatus(415);
                return;
            }
        } catch (IllegalArgumentException exception) {
            response.setStatus(415);
            return;
        }
        if (request.getContentLengthLong() > 16384) {
            response.setStatus(413);
            return;
        }
        long retry = reserve(addresses.of(request));
        if (retry > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retry));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384) {
            response.setStatus(413);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException(); }
                };
            }
        }, response);
    }

    private synchronized long reserve(String address) {
        Instant now = Instant.now();
        if (!now.isBefore(windowEnd)) {
            clients.clear();
            total = 0;
            windowEnd = now.plusSeconds(600);
        }
        if (total >= 100 || clients.getOrDefault(address, 0) >= 5
                || !clients.containsKey(address) && clients.size() >= 4096)
            return Math.max(1, windowEnd.getEpochSecond() - now.getEpochSecond() + 1);
        clients.merge(address, 1, Integer::sum);
        total++;
        return 0;
    }
}
