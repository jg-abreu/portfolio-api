package dev.joaoabreu.portfolioapi.publication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationRequests {
    private final JdbcClient jdbc;
    public PublicationRequests(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed() {
        jdbc.sql("""
                UPDATE publication_requests SET requested_generation=requested_generation+1,
                  next_attempt_at=CASE WHEN lease_token IS NULL AND requested_generation=dispatched_generation
                    THEN CURRENT_TIMESTAMP ELSE next_attempt_at END
                WHERE id=1
                """).update();
    }

    public Optional<Claim> claim() {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE publication_requests SET lease_token=:token,
                  lease_until=CURRENT_TIMESTAMP + INTERVAL '2 minutes', attempts=LEAST(attempts+1, 1000000)
                WHERE id=1 AND requested_generation>dispatched_generation
                  AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                RETURNING requested_generation
                """).param("token", token).query((row, number) -> new Claim(token, row.getLong(1))).optional();
    }

    public void accepted(Claim claim) {
        jdbc.sql("""
                UPDATE publication_requests SET dispatched_generation=GREATEST(dispatched_generation,:generation),
                  lease_token=NULL, lease_until=NULL, attempts=0, last_dispatched_at=CURRENT_TIMESTAMP,
                  next_attempt_at=CURRENT_TIMESTAMP
                WHERE id=1 AND lease_token=:token
                """).param("generation", claim.generation()).param("token", claim.token()).update();
    }

    public void failed(Claim claim) {
        jdbc.sql("""
                UPDATE publication_requests SET lease_token=NULL, lease_until=NULL,
                  next_attempt_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                WHERE id=1 AND lease_token=:token
                """).param("token", claim.token()).update();
    }

    public Status status(boolean enabled) {
        return jdbc.sql("SELECT *, lease_until>CURRENT_TIMESTAMP AS active_lease FROM publication_requests WHERE id=1")
                .query((row, number) -> {
                    long requested = row.getLong("requested_generation"), dispatched = row.getLong("dispatched_generation");
                    String state = !enabled ? "DISABLED" : row.getBoolean("active_lease") ? "DISPATCHING"
                            : requested>dispatched ? "PENDING" : requested == 0 ? "IDLE" : "DISPATCHED";
                    var last = row.getTimestamp("last_dispatched_at");
                    return new Status(enabled, state, requested, dispatched, row.getInt("attempts"),
                            row.getTimestamp("next_attempt_at").toInstant(), last == null ? null : last.toInstant());
                }).single();
    }

    public record Claim(UUID token, long generation) {}
    public record Status(boolean enabled, String state, long requestedGeneration, long dispatchedGeneration,
            int attempts, Instant nextAttemptAt, Instant lastDispatchedAt) {}
}
