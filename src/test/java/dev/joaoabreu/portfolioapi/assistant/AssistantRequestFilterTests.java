package dev.joaoabreu.portfolioapi.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class AssistantRequestFilterTests {
    private AssistantRequestFilter filter() {
        return new AssistantRequestFilter(new AssistantRateLimiter(
                new AssistantRateLimitProperties(10, 100, Duration.ofMinutes(1), 10),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
    }

    @Test
    void boundsActualBytesWhenLengthIsMissingOrIncorrect() throws Exception {
        for (long reported : new long[] {-1, 1}) {
            var request = new MockHttpServletRequest("POST", AssistantController.PATH) {
                @Override public long getContentLengthLong() { return reported; }
                @Override public int getContentLength() { return (int) reported; }
            };
            request.setContentType("application/json");
            request.setContent(new byte[32768]);
            var response = new MockHttpServletResponse();
            filter().doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach controller"); });
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
        }
    }

    @Test
    void countsUtf8BytesRatherThanCharacters() throws Exception {
        var request = new MockHttpServletRequest("POST", AssistantController.PATH);
        request.setContentType("application/json");
        request.setContent("é".repeat(8193).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter().doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach controller"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void ignoresAdministrativeAndOtherRoutesAndMethods() throws Exception {
        for (String path : new String[] {"/api/admin/projects", AssistantController.PATH + "/", AssistantController.PATH + "/extra"}) {
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter().doFilter(new MockHttpServletRequest("POST", path), response, (req, res) -> called.set(true));
            assertThat(called).isTrue();
            assertThat(response.getHeader("Cache-Control")).isNull();
        }
        assertThat(AssistantRequestFilter.matches(new MockHttpServletRequest("GET", AssistantController.PATH))).isFalse();
    }
}
