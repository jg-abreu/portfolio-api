package dev.joaoabreu.portfolioapi.contact;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

public class ContactRetentionJob {
    private final JdbcClient jdbc;

    public ContactRetentionJob(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${portfolio.contact.retention.delay:3600000}")
    public void removeExpired() {
        Instant now = Instant.now();
        jdbc.sql("""
                DELETE FROM contact_messages WHERE created_at < :cutoff
                AND (status <> 'sending' OR next_attempt_at <= :now)
                """).param("cutoff", Timestamp.from(now.minus(90, ChronoUnit.DAYS)))
                .param("now", Timestamp.from(now)).update();
    }
}
