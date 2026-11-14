package dev.joaoabreu.portfolioapi.visits;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "portfolio.visits.enabled", havingValue = "true")
public class VisitCounterService {
    private final JdbcClient jdbc;
    private final Clock clock;
    private static final Duration WINDOW = Duration.ofMinutes(30);

    public VisitCounterService(JdbcClient jdbc, @Qualifier("visitClock") Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Receipt register(byte[] hash) {
        Instant now = clock.instant();
        int changed = jdbc.sql("""
                INSERT INTO visits (ip_hash, last_counted_at) VALUES (:hash, :now)
                ON CONFLICT (ip_hash) DO UPDATE SET last_counted_at = EXCLUDED.last_counted_at
                WHERE visits.last_counted_at <= :cutoff
                """).param("hash", hash).param("now", Timestamp.from(now))
                .param("cutoff", Timestamp.from(now.minus(WINDOW))).update();
        long total = changed == 0 ? total() : jdbc.sql("UPDATE visit_counter SET total=total+1 WHERE id=1 RETURNING total")
                .query(Long.class).single();
        return new Receipt(total, changed != 0);
    }

    public long total() { return jdbc.sql("SELECT total FROM visit_counter WHERE id=1").query(Long.class).single(); }

    @Transactional
    @Scheduled(initialDelayString = "${portfolio.visits.cleanup-delay:60000}", fixedDelayString = "${portfolio.visits.cleanup-delay:60000}")
    public void removeExpired() {
        jdbc.sql("DELETE FROM visits WHERE last_counted_at <= :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(WINDOW))).update();
    }

    public record Receipt(long total, boolean counted) {}
}
