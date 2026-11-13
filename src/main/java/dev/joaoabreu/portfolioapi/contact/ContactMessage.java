package dev.joaoabreu.portfolioapi.contact;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "contact_messages")
public class ContactMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private long version;
    @Column(updatable = false)
    private String name;
    @Column(updatable = false)
    private String email;
    @Column(updatable = false)
    private String message;
    @Column(updatable = false)
    private String status;
    @Column(updatable = false)
    private Instant createdAt;
    private Instant readAt;
    private Instant archivedAt;

    protected ContactMessage() {}

    public long getVersion() { return version; }

    public void setRead(boolean read) {
        if (read != (readAt != null)) readAt = read ? Instant.now() : null;
    }

    public void setArchived(boolean archived) {
        if (archived != (archivedAt != null)) archivedAt = archived ? Instant.now() : null;
    }

    public Summary summary() { return new Summary(id, version, name, createdAt, readAt, archivedAt, status); }
    public Detail detail() { return new Detail(id, version, name, email, message, createdAt, readAt, archivedAt, status); }

    public record Summary(long id, long version, String name, Instant createdAt,
            Instant readAt, Instant archivedAt, String deliveryStatus) {}
    public record Detail(long id, long version, String name, String email, String message, Instant createdAt,
            Instant readAt, Instant archivedAt, String deliveryStatus) {}
}
