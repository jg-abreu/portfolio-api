package dev.joaoabreu.portfolioapi.profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

public class ProfileCardCache {
    private final GitHubProfileSource source;
    private final Clock clock;
    private final AtomicReference<Card> cached = new AtomicReference<>(render(null, null));

    @Autowired
    public ProfileCardCache(GitHubProfileSource source) { this(source, Clock.systemUTC()); }
    ProfileCardCache(GitHubProfileSource source, Clock clock) { this.source = source; this.clock = clock; }

    @Scheduled(initialDelay = 0, fixedDelay = 900000)
    public synchronized void refresh() {
        try { cached.set(render(source.fetch(), clock.instant())); }
        catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    Card current() { return cached.get(); }

    static Card render(GitHubProfileSource.Data data, Instant fetchedAt) {
        String count = data == null ? "Unavailable" : String.format(java.util.Locale.US, "%,d", data.contributions());
        String repo = data == null ? "Unavailable" : data.latestRepository() == null ? "No public repository" : data.latestRepository();
        String status = fetchedAt == null ? "GitHub data not loaded" : "GitHub snapshot: " + fetchedAt.toString();
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="900" height="380" viewBox="0 0 900 380" role="img" aria-labelledby="title desc">
                  <title id="title">João Abreu — Back-end Developer</title>
                  <desc id="desc">Java and Spring Boot developer, Computer Science at UVA. Open to internships.</desc>
                  <defs><linearGradient id="blue"><stop stop-color="#2563eb"/><stop offset="1" stop-color="#60a5fa"/></linearGradient></defs>
                  <rect x="1" y="1" width="898" height="378" rx="24" fill="#0f172a" stroke="#334155"/>
                  <circle cx="810" cy="70" r="95" fill="#2563eb" opacity=".12"/>
                  <rect x="32" y="32" width="64" height="64" rx="16" fill="url(#blue)"/>
                  <g font-family="Arial, sans-serif" fill="#f8fafc">
                    <text x="64" y="73" text-anchor="middle" font-size="24" font-weight="700">JA</text>
                    <text x="116" y="58" font-size="28" font-weight="700">João Abreu</text>
                    <text x="116" y="86" font-size="17" fill="#cbd5e1">Back-end Developer · Java &amp; Spring Boot</text>
                    <text x="32" y="143" font-size="14" fill="#93c5fd">CONTRIBUTIONS · LAST YEAR</text>
                    <text x="32" y="180" font-size="30" font-weight="700">%s</text>
                    <text x="350" y="143" font-size="14" fill="#93c5fd">LATEST PUBLIC REPOSITORY</text>
                    <text x="350" y="178" font-size="18">%s</text>
                    <path d="M32 205h836" stroke="#334155"/>
                    <text x="32" y="239" font-size="17">Java · Spring Boot · PostgreSQL · Docker · Git</text>
                    <text x="32" y="271" font-size="16" fill="#cbd5e1">Computer Science · UVA</text>
                    <text x="32" y="303" font-size="16" fill="#cbd5e1">Currently: inHub · Open to internships</text>
                    <text x="32" y="349" font-size="12" fill="#94a3b8">%s</text>
                    <text x="868" y="349" text-anchor="end" font-size="14" fill="#93c5fd">joaoabreu.dev</text>
                  </g>
                </svg>
                """.formatted(xml(count), xml(shorten(repo)), xml(status));
        try {
            String tag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(svg.getBytes(StandardCharsets.UTF_8))) + "\"";
            return new Card(svg, tag);
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String shorten(String value) { return value.length() <= 44 ? value : value.substring(0, 41) + "…"; }
    static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    record Card(String svg, String etag) {}
}
