package dev.joaoabreu.portfolioapi.profile;

import java.time.Instant;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "profile_card_settings")
public class ProfileCardState {
    static final short ID = 1;

    @Id
    private Short id;
    @Version
    private long version;
    private String draft;
    private String published;
    private Instant publishedAt;
    private Instant updatedAt;

    protected ProfileCardState() {}

    long getVersion() { return version; }
    String getDraft() { return draft; }
    String getPublished() { return published; }
    Instant getPublishedAt() { return publishedAt; }
    Instant getUpdatedAt() { return updatedAt; }

    void saveDraft(String json, Instant now) {
        draft = json;
        updatedAt = now;
    }

    void publish(String json, Instant now) {
        draft = json;
        published = json;
        publishedAt = now;
        updatedAt = now;
    }
}
