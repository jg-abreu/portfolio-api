package dev.joaoabreu.portfolioapi.recommendations;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class GoogleBooksService {
    private final String key;
    private final ObjectMapper json;
    private final Transport transport;
    private final long interval;
    private final AtomicLong next = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public GoogleBooksService(@Value("${portfolio.books.api-key:${GOOGLE_BOOKS_API_KEY:}}") String key, ObjectMapper json) {
        this(key, json, new HttpTransport(), TimeUnit.SECONDS.toNanos(1));
    }

    GoogleBooksService(String key, ObjectMapper json, Transport transport, long interval) {
        this.key = key;
        this.json = json;
        this.transport = transport;
        this.interval = interval;
    }

    public List<Book> search(String query) {
        if (query == null || query.strip().length() < 2 || query.length() > 150)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search requires 2 to 150 characters");
        JsonNode response = request("?q=" + encode(query.strip()) + "&maxResults=12&printType=books");
        JsonNode items = response.get("items");
        if (items == null && response.path("totalItems").asLong(-1) == 0) return List.of();
        if (items == null || !items.isArray() || items.size() > 12) throw unavailable();
        var books = new ArrayList<Book>();
        for (JsonNode item : items) books.add(book(item));
        return books;
    }

    public Book verify(String volumeId) {
        if (!validId(volumeId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid book identifier");
        Book book = book(request("/" + volumeId + "?projection=lite"));
        if (!volumeId.equals(book.volumeId()) || !book.hasCover())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This edition has no cover");
        return book;
    }

    public static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,100}"); }

    private JsonNode request(String path) {
        if (key.isBlank()) throw unavailable();
        long now = System.nanoTime();
        long previous = next.get();
        if (now < previous || !next.compareAndSet(previous, now + interval))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Wait before searching again");
        try {
            var response = transport.get(URI.create("https://www.googleapis.com/books/v1/volumes" + path + "&key=" + encode(key)));
            if (response.status() == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Book provider quota reached");
            if (response.status() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found");
            if (response.status() != 200 || response.body().length > 1024 * 1024) throw unavailable();
            JsonNode value = json.readTree(response.body());
            if (value == null || !value.isObject()) throw unavailable();
            return value;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private Book book(JsonNode item) {
        String id = item.path("id").asText("");
        JsonNode info = item.get("volumeInfo");
        if (!validId(id) || info == null || !info.isObject()) throw unavailable();
        String title = text(info, "title", 300, true);
        var authors = new ArrayList<String>();
        JsonNode values = info.get("authors");
        if (values != null) {
            if (!values.isArray() || values.size() > 30) throw unavailable();
            for (JsonNode author : values) {
                if (!author.isString() || author.asText().length() > 200) throw unavailable();
                authors.add(author.asText());
            }
        }
        boolean hasCover = info.path("imageLinks").path("thumbnail").isString()
                || info.path("imageLinks").path("smallThumbnail").isString();
        return new Book(id, title, authors, text(info, "publisher", 300, false), text(info, "publishedDate", 30, false),
                hasCover, "https://books.google.com/books?id=" + id,
                hasCover ? "https://books.google.com/books/content?id=" + id + "&printsec=frontcover&img=1&zoom=1&source=gbs_api" : "");
    }

    private String text(JsonNode info, String field, int maximum, boolean required) {
        JsonNode value = info.get(field);
        if (value == null && !required) return "";
        if (value == null || !value.isString() || value.asText().length() > maximum || (required && value.asText().isBlank())) throw unavailable();
        return value.asText();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Book search is unavailable"); }
    public record Book(String volumeId, String title, List<String> authors, String publisher, String publishedDate,
            boolean hasCover, String infoUrl, String imageUrl) {}
    record Reply(int status, byte[] body) {}
    interface Transport { Reply get(URI uri) throws Exception; }

    static class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        public Reply get(URI uri) {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build();
            var response = client.sendAsync(request, info -> new LimitedBody()).orTimeout(10, TimeUnit.SECONDS).join();
            return new Reply(response.statusCode(), response.body());
        }
    }

    static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 1024 * 1024) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
