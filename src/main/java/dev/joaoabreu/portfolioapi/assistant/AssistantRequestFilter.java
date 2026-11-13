package dev.joaoabreu.portfolioapi.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class AssistantRequestFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 16 * 1024;
    private final AssistantRateLimiter limiter;

    public AssistantRequestFilter(AssistantRateLimiter limiter) { this.limiter = limiter; }

    public static boolean matches(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && (request.getContextPath() + AssistantController.PATH).equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !matches(request); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response, AssistantException.Code.REQUEST_TOO_LARGE);
            return;
        }
        if (!supported(request)) {
            reject(response, AssistantException.Code.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        // Use the servlet-provided address. Do not parse spoofable forwarding headers here.
        long retry = limiter.acquire(request.getRemoteAddr());
        if (retry > 0) {
            response.setHeader("Retry-After", Long.toString(retry));
            reject(response, AssistantException.Code.RATE_LIMITED);
            return;
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            reject(response, AssistantException.Code.INVALID_REQUEST);
            return;
        }
        if (body.length > MAX_BODY_BYTES) {
            reject(response, AssistantException.Code.REQUEST_TOO_LARGE);
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private static boolean supported(HttpServletRequest request) {
        try {
            if (request.getContentType() == null) return false;
            MediaType type = MediaType.parseMediaType(request.getContentType());
            String encoding = request.getHeader("Content-Encoding");
            return type.getType().equalsIgnoreCase("application") && type.getSubtype().equalsIgnoreCase("json")
                    && (type.getCharset() == null || type.getCharset().equals(StandardCharsets.UTF_8))
                    && (encoding == null || encoding.equalsIgnoreCase("identity"));
        } catch (IllegalArgumentException exception) { return false; }
    }

    private static void reject(HttpServletResponse response, AssistantException.Code code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(AssistantJson.MAPPER.writeValueAsString(AssistantProblem.of(code, List.of())));
    }

    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }

        @Override public long getContentLengthLong() { return body.length; }
        @Override public int getContentLength() { return body.length; }
        @Override public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Only synchronous request reading is supported");
                }
            };
        }
    }
}
