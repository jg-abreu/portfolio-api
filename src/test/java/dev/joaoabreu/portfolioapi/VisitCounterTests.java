package dev.joaoabreu.portfolioapi;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import dev.joaoabreu.portfolioapi.visits.VisitClientAddress;
import dev.joaoabreu.portfolioapi.visits.VisitConfiguration;
import dev.joaoabreu.portfolioapi.visits.VisitCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "server.servlet.session.cookie.secure=false", "portfolio.visits.enabled=true", "portfolio.visits.hmac-key=synthetic-test-key-not-a-secret-1234567890",
        "portfolio.visits.cleanup-delay=3600000", "portfolio.contact.mail.enabled=false",
        "portfolio.contact.retention.enabled=false"
})
class VisitCounterTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired VisitCounterService counter;
    @MockitoBean(name = "visitClock") Clock clock;
    final AtomicReference<Instant> now = new AtomicReference<>();
    MockMvc mvc;

    @BeforeEach
    void setup() {
        now.set(Instant.parse("2026-11-13T10:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM visits").update();
        jdbc.sql("UPDATE visit_counter SET total=0 WHERE id=1").update();
    }

    @Test
    void countsAtThirtyMinutesWithoutExtendingTheWindowOnIntermediateAccess() throws Exception {
        mvc.perform(visit("192.0.2.1")).andExpect(status().isOk()).andExpect(jsonPath("$.counted").value(true));
        now.set(now.get().plusSeconds(1799));
        mvc.perform(visit("192.0.2.1")).andExpect(jsonPath("$.counted").value(false));
        assertEquals(Instant.parse("2026-11-13T10:00:00Z"),
                jdbc.sql("SELECT last_counted_at FROM visits").query(java.time.OffsetDateTime.class).single().toInstant());
        now.set(now.get().plusSeconds(1));
        mvc.perform(visit("192.0.2.1")).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.counted").value(true));
    }

    @Test
    void concurrentRequestsCountOneWindowAndPreserveEveryDistinctIncrement() throws Exception {
        byte[] same = new byte[32];
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> calls = new ArrayList<>();
            for (int index = 0; index < 20; index++) calls.add(pool.submit(() -> {
                start.await();
                counter.register(same);
                return null;
            }));
            start.countDown();
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
            assertEquals(1, counter.total());
            calls.clear();
            for (int index = 1; index <= 10; index++) {
                byte[] distinct = new byte[32];
                distinct[31] = (byte) index;
                calls.add(pool.submit(() -> counter.register(distinct)));
            }
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
            assertEquals(11, counter.total());
        }
    }

    @Test
    void cleanupRemovesExpiredHashesWithoutResettingTheTotal() throws Exception {
        mvc.perform(visit("192.0.2.2")).andExpect(status().isOk());
        now.set(now.get().plus(30, ChronoUnit.MINUTES));
        counter.removeExpired();
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM visits").query(Long.class).single());
        assertEquals(1, counter.total());
        mvc.perform(visit("192.0.2.2")).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void usesHmacOfTheActualPeerAndIgnoresSpoofedHeadersInDirectMode() throws Exception {
        mvc.perform(visit("192.0.2.3").header("CF-Connecting-IP", "198.51.100.1").header("X-Forwarded-For", "198.51.100.2"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ip").doesNotExist()).andExpect(jsonPath("$.hash").doesNotExist());
        mvc.perform(visit("192.0.2.3").header("CF-Connecting-IP", "198.51.100.3"))
                .andExpect(jsonPath("$.counted").value(false));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("synthetic-test-key-not-a-secret-1234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        assertArrayEquals(mac.doFinal(VisitClientAddress.literal("192.0.2.3")),
                jdbc.sql("SELECT ip_hash FROM visits").query((row, number) -> row.getBytes(1)).single());
    }

    @Test
    void equivalentIpv6FormsShareOneWindowAndBotsAreSkipped() throws Exception {
        for (String bot : new String[]{"Googlebot", "HeadlessChrome", "curl/8", ""})
            mvc.perform(visit("192.0.2.4").with(request -> { request.removeHeader("User-Agent"); request.addHeader("User-Agent", bot); return request; })).andExpect(jsonPath("$.counted").value(false));
        assertEquals(0, counter.total());
        mvc.perform(visit("2001:db8::1")).andExpect(jsonPath("$.counted").value(true));
        mvc.perform(visit("2001:0db8:0000:0000:0000:0000:0000:0001")).andExpect(jsonPath("$.counted").value(false));
        assertEquals(1, counter.total());
    }

    @Test
    void requiresCsrfForRegistrationButAllowsReadingTheTotal() throws Exception {
        mvc.perform(post("/api/visits").header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/visits")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, counter.total());
    }

    @Test
    void cloudflareModeRequiresBothTrustedHopsAndStrictLiteralAddresses() {
        var properties = new VisitConfiguration.Properties("synthetic-key-not-secret-1234567890",
                VisitConfiguration.IpSource.CLOUDFLARE_PROXY, List.of("127.0.0.1/32"), List.of("203.0.113.0/24"));
        var resolver = new VisitClientAddress(properties);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.4");
        request.addHeader("CF-Connecting-IP", "2001:db8::5");
        assertArrayEquals(VisitClientAddress.literal("2001:db8::5"), resolver.resolve(request));
        request.setRemoteAddr("192.0.2.8");
        assertThrows(ResponseStatusException.class, () -> resolver.resolve(request));
        request.setRemoteAddr("127.0.0.1");
        request.removeHeader("X-Real-IP");
        request.addHeader("X-Real-IP", "198.51.100.2");
        assertThrows(ResponseStatusException.class, () -> resolver.resolve(request));
        for (String invalid : new String[]{"example.com", "1.2.3.999", "1.2.3.4,5.6.7.8", "fe80::1%eth0", ""})
            assertThrows(ResponseStatusException.class, () -> VisitClientAddress.literal(invalid));
        assertThrows(IllegalArgumentException.class, () -> new VisitConfiguration.Properties("short",
                VisitConfiguration.IpSource.DIRECT, List.of(), List.of()));
    }

    @Test
    void registersThroughRealHttpCsrfSessionWithoutDoubleCounting() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            var csrfResponse = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrfResponse.statusCode());
            var csrf = json.readTree(csrfResponse.body());
            for (int attempt = 0; attempt < 2; attempt++) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/visits"))
                        .header("User-Agent", "Mozilla/5.0")
                        .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                var receipt = json.readTree(response.body());
                assertEquals(1, receipt.get("total").asInt());
                assertEquals(attempt == 0, receipt.get("counted").asBoolean());
            }
            assertEquals(1, counter.total());
        }
    }

    private MockHttpServletRequestBuilder visit(String ip) {
        return post("/api/visits").with(csrf()).header("User-Agent", "Mozilla/5.0")
                .with(request -> { request.setRemoteAddr(ip); return request; });
    }
}
