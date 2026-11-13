package dev.joaoabreu.portfolioapi.publication;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

public class GitHubWorkflow {
    static final URI TARGET = URI.create("https://api.github.com/repos/jg-abreu/portfolio/actions/workflows/publication-v2.yml/dispatches");
    static final String BODY = "{\"ref\":\"feat/v2\"}";
    private final String token;
    private final Transport transport;

    @Autowired
    public GitHubWorkflow(@Value("${portfolio.publication.github-token:${PUBLICATION_GITHUB_TOKEN:}}") String token) {
        this(token, new HttpTransport());
    }
    GitHubWorkflow(String token, Transport transport) {
        if (token.isBlank()) throw new IllegalArgumentException("Configure the publication token before enabling dispatch");
        this.token = token; this.transport = transport;
    }
    public void dispatch() throws Exception {
        var request = HttpRequest.newBuilder(TARGET).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("User-Agent", "jg-abreu-portfolio-publication")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        int status = transport.send(request);
        if (status != 204 && status != 200) throw new IllegalStateException("Publication dispatch was not accepted");
    }
    interface Transport { int send(HttpRequest request) throws Exception; }
    static class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        public int send(HttpRequest request) throws Exception {
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            try { return pending.get(10, TimeUnit.SECONDS).statusCode(); }
            finally { if (!pending.isDone()) pending.cancel(true); }
        }
    }
}
