package dev.joaoabreu.portfolioapi.contact;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "portfolio.contact.mail.enabled", havingValue = "true")
public class ContactDeliveryWorker {
    private final JdbcClient jdbc;
    private final SmtpContactNotifier notifier;

    public ContactDeliveryWorker(JdbcClient jdbc, SmtpContactNotifier notifier) {
        this.jdbc = jdbc;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${portfolio.contact.mail.poll-delay:60000}")
    public void deliver() {
        jdbc.sql("""
                UPDATE contact_messages SET status = 'failed'
                WHERE status IN ('pending', 'sending') AND attempts >= 5 AND next_attempt_at <= :now
                """).param("now", Timestamp.from(Instant.now())).update();
        for (int index = 0; index < 10; index++) {
            Instant now = Instant.now();
            var claim = jdbc.sql("""
                    UPDATE contact_messages SET status = 'sending', attempts = attempts + 1, next_attempt_at = :lease
                    WHERE id = (SELECT id FROM contact_messages
                    WHERE status IN ('pending', 'sending') AND attempts < 5 AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING id, name, email, message, created_at, attempts
                    """).param("now", Timestamp.from(now)).param("lease", Timestamp.from(now.plusSeconds(300)))
                    .query((row, number) -> new Claim(row.getLong("id"), row.getString("name"),
                            row.getString("email"), row.getString("message"), row.getTimestamp("created_at").toInstant(),
                            row.getInt("attempts"))).optional();
            if (claim.isEmpty()) return;
            var item = claim.get();
            try {
                notifier.send(new ContactMessage.Detail(item.id(), 0, item.name(), item.email(), item.message(),
                        item.createdAt(), null, null, "sending"));
                jdbc.sql("""
                        UPDATE contact_messages SET status = 'sent', sent_at = :now
                        WHERE id = :id AND status = 'sending' AND attempts = :attempts
                        """).param("now", Timestamp.from(Instant.now())).param("id", item.id())
                        .param("attempts", item.attempts()).update();
            } catch (MailException | IllegalArgumentException exception) {
                jdbc.sql("""
                        UPDATE contact_messages SET status = :status, next_attempt_at = :next
                        WHERE id = :id AND status = 'sending' AND attempts = :attempts
                        """).param("status", item.attempts() >= 5 ? "failed" : "pending")
                        .param("next", Timestamp.from(Instant.now().plusSeconds(60L << Math.min(item.attempts() - 1, 8))))
                        .param("id", item.id()).param("attempts", item.attempts()).update();
            }
        }
    }

    private record Claim(long id, String name, String email, String message, Instant createdAt, int attempts) {}
}
