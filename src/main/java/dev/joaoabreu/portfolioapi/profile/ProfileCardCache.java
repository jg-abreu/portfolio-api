package dev.joaoabreu.portfolioapi.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

public class ProfileCardCache {
    private final GitHubProfileSource source;
    private final Clock clock;
    private final Supplier<ProfileCardSettings> settings;
    private volatile Snapshot snapshot;
    private final AtomicReference<Card> cached = new AtomicReference<>();

    @Autowired
    public ProfileCardCache(GitHubProfileSource source, ProfileCardSettingsService settings) {
        this(source, Clock.systemUTC(), settings::published);
    }

    ProfileCardCache(GitHubProfileSource source, Clock clock) { this(source, clock, () -> ProfileCardSettings.DEFAULTS); }

    ProfileCardCache(GitHubProfileSource source, Clock clock, Supplier<ProfileCardSettings> settings) {
        this.source = source;
        this.clock = clock;
        this.settings = settings;
        rerender();
    }

    @Scheduled(initialDelay = 0, fixedDelay = 900000)
    public synchronized void refresh() {
        try {
            snapshot = new Snapshot(source.fetch(), clock.instant());
            rerender();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onPublished(ProfileCardSettingsService.Published event) { rerender(); }

    synchronized void rerender() {
        Snapshot current = snapshot;
        cached.set(ProfileCardRenderer.render(settings.get(),
                current == null ? null : current.data(), current == null ? null : current.fetchedAt()));
    }

    Card current() { return cached.get(); }

    Snapshot snapshot() { return snapshot; }

    static Card render(GitHubProfileSource.Data data, Instant fetchedAt) {
        return ProfileCardRenderer.render(ProfileCardSettings.DEFAULTS, data, fetchedAt);
    }

    static String xml(String value) { return ProfileCardRenderer.xml(value); }

    record Card(String svg, String etag) {}

    record Snapshot(GitHubProfileSource.Data data, Instant fetchedAt) {}
}
