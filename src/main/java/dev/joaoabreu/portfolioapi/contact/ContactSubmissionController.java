package dev.joaoabreu.portfolioapi.contact;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "portfolio.contact.submission.enabled", havingValue = "true")
public class ContactSubmissionController {
    private final JdbcClient jdbc;

    public ContactSubmissionController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @PostMapping(path = "/api/contact", consumes = "application/json", produces = "application/json")
    ResponseEntity<Receipt> submit(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Submission request) {
        if (request.email().indexOf('\r') >= 0 || request.email().indexOf('\n') >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int inserted = jdbc.sql("""
                INSERT INTO contact_messages (name, email, message, submission_key)
                VALUES (:name, :email, :message, :key)
                ON CONFLICT (submission_key) DO NOTHING
                """).param("name", request.name()).param("email", request.email())
                .param("message", request.message()).param("key", key).update();
        if (inserted == 0) {
            boolean same = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM contact_messages
                    WHERE submission_key = :key AND name = :name AND email = :email AND message = :message)
                    """).param("key", key).param("name", request.name()).param("email", request.email())
                    .param("message", request.message()).query(Boolean.class).single();
            if (!same) throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return ResponseEntity.accepted().body(new Receipt("accepted"));
    }

    public record Submission(@NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 5000) String message,
            @Size(max = 0) String website) {
        public Submission {
            name = name == null ? null : name.strip();
            email = email == null ? null : email.strip();
            message = message == null ? null : message.strip();
        }
    }

    public record Receipt(String status) {}
}
