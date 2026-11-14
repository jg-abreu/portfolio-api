package dev.joaoabreu.portfolioapi.profile;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Flow;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileCardTests {
    final ObjectMapper json = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    String response(String total, String repos) {
        return "{\"data\":{\"user\":{\"login\":\"jg-abreu\",\"contributionsCollection\":{\"contributionCalendar\":{\"totalContributions\":"
                + total + "}},\"repositories\":{\"nodes\":" + repos + "}}}}";
    }
    String repo = "[{\"name\":\"synthetic-project\",\"isPrivate\":false,\"pushedAt\":\"2026-11-12T12:00:00Z\",\"owner\":{\"login\":\"jg-abreu\"}}]";
    GitHubProfileSource source(String body) {
        return new GitHubProfileSource("synthetic-token", json, request ->
                new GitHubProfileSource.Reply(200, body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void requestsFixedPublicOwnerQueryAndValidatesProviderData() throws Exception {
        var source = new GitHubProfileSource("synthetic-token", json, request -> {
            assertEquals("https://api.github.com/graphql", request.uri().toString());
            assertEquals("POST", request.method());
            assertEquals("Bearer synthetic-token", request.headers().firstValue("Authorization").orElseThrow());
            assertTrue(GitHubProfileSource.QUERY.contains("privacy: PUBLIC"));
            assertTrue(GitHubProfileSource.QUERY.contains("ownerAffiliations: OWNER"));
            return new GitHubProfileSource.Reply(200, response("1234", repo).getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(new GitHubProfileSource.Data(1234, "synthetic-project"), source.fetch());
    }

    @Test
    void emptyPublicRepositoryListDoesNotInventAProject() throws Exception {
        assertNull(source(response("0", "[]")).fetch().latestRepository());
    }

    @Test
    void rejectsPartialErrorsWrongIdentityPrivateRepositoriesAndMalformedCounts() {
        for (String body : List.of("{\"errors\":[{}]}", "{}", response("-1", repo), response("1.5", repo),
                response("2147483648", repo), response("12", repo).replace("false", "true"),
                response("12", repo).replace("jg-abreu", "other"), response("12", repo).replace("synthetic-project", "<script>")))
            assertThrows(Exception.class, () -> source(body).fetch());
    }

    @Test
    void absentTokenAndProviderFailuresAreUnavailable() {
        var transport = mock(GitHubProfileSource.Transport.class);
        assertThrows(Exception.class, () -> new GitHubProfileSource("", json, transport).fetch());
        verifyNoInteractions(transport);
        for (int status : List.of(301, 401, 429, 500)) {
            var source = new GitHubProfileSource("synthetic-token", json, request -> new GitHubProfileSource.Reply(status, new byte[0]));
            assertThrows(Exception.class, source::fetch);
        }
        assertThrows(Exception.class, () -> source(" ".repeat(65537)).fetch());
    }

    @Test
    void boundedSubscriberCancelsOversizedResponses() {
        var body = new GitHubProfileSource.LimitedBody();
        var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[65537])));
        verify(subscription).cancel();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        var valid = new GitHubProfileSource.LimitedBody();
        valid.onSubscribe(mock(Flow.Subscription.class));
        valid.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        valid.onComplete();
        assertArrayEquals(new byte[] {1, 2}, valid.getBody().toCompletableFuture().join());
    }

    @Test
    void coldCacheNeverInventsNumbersAndReadsNeverCallGitHub() throws Exception {
        var source = mock(GitHubProfileSource.class);
        var cache = new ProfileCardCache(source, clock);
        assertTrue(cache.current().svg().contains("GitHub data not loaded"));
        assertFalse(cache.current().svg().contains("synthetic-project"));
        for (int i = 0; i < 100; i++) cache.current();
        verifyNoInteractions(source);
    }

    @Test
    void failedRefreshKeepsLastGoodSnapshotAndRecovers() throws Exception {
        var source = mock(GitHubProfileSource.class);
        when(source.fetch()).thenReturn(new GitHubProfileSource.Data(1234, "synthetic-project"))
                .thenThrow(new IllegalStateException("synthetic failure"))
                .thenReturn(new GitHubProfileSource.Data(1235, "new-project"));
        var cache = new ProfileCardCache(source, clock);
        cache.refresh();
        var first = cache.current();
        assertTrue(first.svg().contains("1,234"));
        cache.refresh();
        assertEquals(first, cache.current());
        cache.refresh();
        assertTrue(cache.current().svg().contains("1,235"));
        assertNotEquals(first.etag(), cache.current().etag());
    }

    @Test
    void svgIsParseableEscapesEveryDynamicTextAndHasNoExternalResources() throws Exception {
        var card = ProfileCardCache.render(new GitHubProfileSource.Data(0, "<script>&\"'"), clock.instant());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(card.svg())));
        assertEquals("svg", document.getDocumentElement().getNodeName());
        assertEquals(0, document.getElementsByTagName("script").getLength());
        assertTrue(card.svg().contains("&lt;script&gt;&amp;&quot;&apos;"));
        assertFalse(card.svg().contains("href="));
        assertTrue(card.etag().matches("\"[0-9a-f]{64}\""));
    }

    @Test
    void httpUsesCachedSvgWithConditionalGetAndSecurityHeaders() throws Exception {
        var source = mock(GitHubProfileSource.class);
        var cache = new ProfileCardCache(source, clock);
        var mvc = MockMvcBuilders.standaloneSetup(new ProfileCardController(cache)).build();
        mvc.perform(get("/api/profile-card.svg")).andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "public, max-age=300, s-maxage=300, must-revalidate"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("ETag", cache.current().etag()));
        mvc.perform(get("/api/profile-card.svg").header("If-None-Match", cache.current().etag()))
                .andExpect(status().isNotModified()).andExpect(content().string(""));
        verifyNoInteractions(source);
    }
}
