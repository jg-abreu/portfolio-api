package dev.joaoabreu.portfolioapi.recommendations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class RecommendationModels {
    private RecommendationModels() {}
    public enum Status { DRAFT, ARCHIVED }
    public enum SectionKind { BOOKS, ITEMS, LINK }
    public enum BookGroup { TECHNICAL, NON_TECHNICAL }
    public enum ReadLanguage { PT, EN }

    public record SectionTitle(@NotBlank @Size(max = 80) String title, @Size(max = 200) String description) {
        public SectionTitle { description = description == null ? "" : description.strip(); title = title == null ? null : title.strip(); }
    }
    public record SectionData(@NotNull SectionKind kind, @NotNull @Valid SectionTitle en, @NotNull @Valid SectionTitle pt,
            @Size(max = 60) @Pattern(regexp = "(?:|[a-z0-9]+(-[a-z0-9]+)*)") String slug, @Size(max = 1000) String url) {
        public SectionData { slug = slug == null ? "" : slug; url = url == null ? "" : url.strip(); }
    }
    public record SaveSection(@NotNull @Min(0) Long version, @NotNull @Valid SectionData data) {}
    public record Section(long id, long version, int displayOrder, SectionData data, long items) {}
    public record SectionOrder(@NotNull @Size(min = 1, max = 50) List<@NotNull Long> ids) {}
    public record ItemOrder(@NotNull Long sectionId, @NotNull @Size(min = 1, max = 500) List<@NotNull Long> ids) {}

    public record Copy(@NotNull @Size(max = 150) String title,
            @NotNull @Size(max = 300) String description,
            @NotNull @Size(max = 200) String imageAlt) {
        boolean complete() { return !title.isBlank() && !description.isBlank(); }
    }
    public record Details(
            @NotNull @Size(max = 100) @Pattern(regexp = "(?:|[a-z0-9]+(-[a-z0-9]+)*)") String slug,
            @NotNull Long sectionId,
            @NotNull @Pattern(regexp = "(?:|[0-9a-f]{64}\\.png)") String imageKey,
            @NotNull @Size(max = 1000) String url,
            @DecimalMin("0") @DecimalMax("5") double rating,
            BookGroup bookGroup,
            ReadLanguage readIn,
            @NotNull @Size(max = 150) String subtitle,
            @NotNull @Valid Copy en, @NotNull @Valid Copy pt,
            @Pattern(regexp = "(?:|[A-Za-z0-9_-]{1,100})") String googleBooksId) {}
    public record Save(@NotNull @Min(0) Long version, @NotNull @Valid Details data) {}
    public record Version(@NotNull @Min(0) Long version) {}
    public record ChangeStatus(@NotNull @Min(0) Long version, @NotNull Status status) {}
    public record Draft(long id, long version, Status status, int displayOrder, Details data, Instant updatedAt) {}
    public record Publication(long recommendationId, String revision, long draftVersion, Details data) {}
    public record PublicSection(long id, String slug, SectionKind kind, String url, SectionTitle en, SectionTitle pt, List<Publication> items) {}
    public record Snapshot(int schemaVersion, List<PublicSection> sections) {}
    public record Summary(long recommendationId, String revision, long draftVersion) {}
}
