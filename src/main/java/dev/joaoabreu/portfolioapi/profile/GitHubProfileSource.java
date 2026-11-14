package dev.joaoabreu.portfolioapi.profile;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

public class GitHubProfileSource {
    static final String QUERY = """
            { user(login: "jg-abreu") {
              login
              contributionsCollection { contributionCalendar { totalContributions } }
              repositories(first: 1, privacy: PUBLIC, ownerAffiliations: OWNER,
                orderBy: {field: PUSHED_AT, direction: DESC}) {
                nodes { name isPrivate pushedAt owner { login } }
              }
            } }
            """;
    private final String token;
    private final ObjectMapper json;
    private final Transport transport;

    @Autowired
    public GitHubProfileSource(@Value("${portfolio.profile-card.github-token:${PROFILE_CARD_GITHUB_TOKEN:}}") String token,
            ObjectMapper json) {
        this(token, json, new HttpTransport());
    }

    GitHubProfileSource(String token, ObjectMapper json, Transport transport) {
        this.token = token;
        this.json = json;
        this.transport = transport;
    }

    Data fetch() throws Exception {
        if (token.isBlank()) throw new IllegalStateException("Profile token is not configured");
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/graphql"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json").header("Content-Type", "application/json")
                .header("User-Agent", "jg-abreu-portfolio-card")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("query", QUERY)))).build();
        var reply = transport.send(request);
        if (reply.status() != 200 || reply.body().length > 65536) throw new IllegalStateException("Profile source unavailable");
        var value = json.readTree(reply.body());
        if (value == null || !value.isObject() || value.has("errors")) throw new IllegalStateException("Invalid profile response");
        var user = value.path("data").path("user");
        var total = user.path("contributionsCollection").path("contributionCalendar").path("totalContributions");
        var repos = user.path("repositories").path("nodes");
        if (!"jg-abreu".equalsIgnoreCase(user.path("login").asText()) || !total.isIntegralNumber()
                || !total.canConvertToInt() || total.asInt() < 0 || !repos.isArray() || repos.size() > 1)
            throw new IllegalStateException("Invalid profile data");
        String latest = null;
        if (!repos.isEmpty()) {
            var repo = repos.get(0);
            var name = repo.path("name");
            if (!name.isString() || !name.asText().matches("[A-Za-z0-9_.-]{1,100}")
                    || !repo.path("isPrivate").isBoolean() || repo.path("isPrivate").asBoolean()
                    || !"jg-abreu".equalsIgnoreCase(repo.path("owner").path("login").asText()))
                throw new IllegalStateException("Invalid public repository");
            if (!repo.path("pushedAt").isNull()) Instant.parse(repo.path("pushedAt").asText());
            latest = name.asText();
        }
        return new Data(total.asInt(), latest);
    }

    record Data(int contributions, String latestRepository) {}
    record Reply(int status, byte[] body) {}
    interface Transport { Reply send(HttpRequest request) throws Exception; }

    static class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        public Reply send(HttpRequest request) throws Exception {
            var pending = client.sendAsync(request, info -> new LimitedBody());
            try {
                var response = pending.get(8, TimeUnit.SECONDS);
                return new Reply(response.statusCode(), response.body());
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        }
    }

    static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 65536) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Profile response too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
