package dev.joaoabreu.portfolioapi.recommendations;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class GoogleBooksTests {
    final ObjectMapper json = new ObjectMapper();
    String volume(String id, boolean cover) {
        return "{\"id\":\"" + id + "\",\"volumeInfo\":{\"title\":\"Synthetic book\",\"authors\":[\"Test author\"],"
                + "\"publisher\":\"Test publisher\",\"publishedDate\":\"2026\""
                + (cover ? ",\"imageLinks\":{\"thumbnail\":\"http://untrusted.invalid/image\"}" : "") + "}}";
    }
    GoogleBooksService.Reply reply(int status, String body) {
        return new GoogleBooksService.Reply(status, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void searchPreservesProviderOrderAndBuildsOnlyFixedGoogleLinks() {
        var books = new GoogleBooksService("synthetic-key", json, uri -> {
            assertEquals("www.googleapis.com", uri.getHost());
            assertTrue(uri.getRawQuery().contains("q=Java+%26+Spring"));
            return reply(200, "{\"items\":[" + volume("second", true) + "," + volume("first", false) + "]}");
        }, 0);
        var results = books.search("Java & Spring");
        assertEquals(List.of("second", "first"), results.stream().map(GoogleBooksService.Book::volumeId).toList());
        assertTrue(results.getFirst().imageUrl().startsWith("https://books.google.com/books/content?id=second&"));
        assertFalse(results.get(1).hasCover());
        assertEquals("", results.get(1).imageUrl());
        assertEquals("https://books.google.com/books?id=second", results.getFirst().infoUrl());
    }

    @Test
    void selectionVerifiesTheRequestedEditionAndCover() {
        var valid = new GoogleBooksService("test", json, uri -> reply(200, volume("edition", true)), 0);
        assertEquals("edition", valid.verify("edition").volumeId());
        var wrong = new GoogleBooksService("test", json, uri -> reply(200, volume("other", true)), 0);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> wrong.verify("edition")).getStatusCode().value());
        var missing = new GoogleBooksService("test", json, uri -> reply(200, volume("edition", false)), 0);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> missing.verify("edition")).getStatusCode().value());
    }

    @Test
    void noConfigurationOrInvalidInputNeverCallsTheProvider() {
        var books = new GoogleBooksService("", json, uri -> { fail("No remote call expected"); return null; }, 0);
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> books.search("Java")).getStatusCode().value());
        for (String query : new String[] { "", "x", "a".repeat(151) })
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> books.search(query)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> books.verify("../../private")).getStatusCode().value());
    }

    @Test
    void providerFailuresAndOversizedOrMalformedResponsesDoNotLeakDetails() {
        for (var response : List.of(reply(503, "private provider error"), reply(200, "invalid"), reply(200, "[]"),
                reply(200, "{\"items\":[{}]}"), new GoogleBooksService.Reply(200, new byte[1024 * 1024 + 1]))) {
            var books = new GoogleBooksService("secret-test", json, uri -> response, 0);
            var error = assertThrows(ResponseStatusException.class, () -> books.search("Java"));
            assertEquals(503, error.getStatusCode().value());
            assertFalse(error.getMessage().contains("secret-test"));
            assertNull(error.getCause());
        }
        var limited = new GoogleBooksService("test", json, uri -> reply(429, "quota"), 0);
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> limited.search("Java")).getStatusCode().value());
    }

    @Test
    void repeatedSearchesAreThrottledWithoutExtraProviderCalls() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var books = new GoogleBooksService("test", json, uri -> { count.incrementAndGet(); return reply(200, "{\"totalItems\":0}"); }, Long.MAX_VALUE / 4);
        assertTrue(books.search("Java").isEmpty());
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> books.search("Java")).getStatusCode().value());
        assertEquals(1, count.get());
    }

    @Test
    void bodyLimitCancelsAnOversizedStreamBeforeKeepingIt() {
        var body = new GoogleBooksService.LimitedBody();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[1024 * 1024 + 1])));
        assertTrue(cancelled.get());
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
