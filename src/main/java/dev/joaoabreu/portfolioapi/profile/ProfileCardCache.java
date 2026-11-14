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
                <svg xmlns="http://www.w3.org/2000/svg" width="900" height="720" viewBox="0 0 900 720" role="img" aria-labelledby="title desc">
                  <title id="title">João Abreu — Back-end Developer</title>
                  <desc id="desc">Java and Spring Boot developer, Computer Science at UVA. Open to internships. Public GitHub snapshot.</desc>
                  <rect x="1" y="1" width="898" height="718" rx="18" fill="#0a0a0a" stroke="#292929"/>
                  <g font-family="Arial, sans-serif">
                    <circle cx="80" cy="80" r="39" fill="#111a2b" stroke="#3b82f6" stroke-width="2"/>
                    <text x="80" y="89" text-anchor="middle" font-size="25" font-weight="700" fill="#93c5fd">JA</text>
                    <text x="142" y="76" font-size="32" font-weight="700" fill="#fafafa">João Abreu</text>
                    <text x="142" y="104" font-size="17" fill="#b0b0b0">Building back-end systems with Java &amp; Spring Boot.</text>
                    <g fill="#171717" stroke="#292929">
                      <rect x="40" y="150" width="400" height="120" rx="12"/>
                      <rect x="460" y="150" width="400" height="120" rx="12"/>
                      <rect x="40" y="290" width="820" height="116" rx="12"/>
                      <rect x="40" y="426" width="400" height="218" rx="12"/>
                      <rect x="460" y="426" width="400" height="100" rx="12"/>
                      <rect x="460" y="546" width="400" height="98" rx="12"/>
                    </g>
                    <g fill="#b0b0b0" font-size="12" font-weight="700" letter-spacing="1">
                      <text x="62" y="184">CONTRIBUTIONS · LAST YEAR</text>
                      <text x="482" y="184">LATEST PUBLIC REPOSITORY</text>
                      <text x="62" y="323">TECH STACK</text>
                      <text x="62" y="459">EDUCATION &amp; PROJECTS</text>
                      <text x="482" y="459">CURRENT FOCUS</text>
                      <text x="482" y="579">OPEN TO OPPORTUNITIES</text>
                    </g>
                    <text x="62" y="235" font-size="36" font-weight="700" fill="#60a5fa">%s</text>
                    <text x="482" y="222" font-family="monospace" font-size="16" font-weight="700" fill="#fafafa">%s</text>
                    <g font-size="14" font-weight="700" text-anchor="middle">
                      <rect x="62" y="345" width="119" height="38" rx="8" fill="#242424"/>
                      <rect x="193" y="345" width="166" height="38" rx="8" fill="#242424"/>
                      <rect x="371" y="345" width="174" height="38" rx="8" fill="#242424"/>
                      <rect x="557" y="345" width="153" height="38" rx="8" fill="#242424"/>
                      <rect x="722" y="345" width="116" height="38" rx="8" fill="#242424"/>
                      <text x="121" y="370" fill="#93c5fd">Java</text>
                      <text x="276" y="370" fill="#a3d977">Spring Boot</text>
                      <text x="458" y="370" fill="#93c5fd">PostgreSQL</text>
                      <text x="633" y="370" fill="#7dd3fc">Docker</text>
                      <text x="780" y="370" fill="#f5a38e">Git</text>
                    </g>
                    <g fill="#fafafa" font-size="16" font-weight="700">
                      <text x="62" y="495">Computer Science · UVA</text>
                      <text x="62" y="563">Tutoring Management System</text>
                      <text x="482" y="495">inHub</text>
                      <text x="482" y="615">Java back-end internship</text>
                    </g>
                    <g fill="#b0b0b0" font-size="14">
                      <text x="62" y="520">Rio de Janeiro, Brazil</text>
                      <text x="62" y="589">Java · Spring Boot · PostgreSQL</text>
                      <text x="62" y="614">Personal project</text>
                    </g>
                    <text x="40" y="682" font-size="11" fill="#a3a3a3">%s</text>
                    <text x="860" y="682" text-anchor="end" font-size="13" fill="#93c5fd">joaoabreu.dev</text>
                  </g>
                </svg>
                """.formatted(xml(count), xml(shorten(repo)), xml(status));
        try {
            String tag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(svg.getBytes(StandardCharsets.UTF_8))) + "\"";
            return new Card(svg, tag);
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String shorten(String value) { return value.length() <= 32 ? value : value.substring(0, 29) + "…"; }
    static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    record Card(String svg, String etag) {}
}
