package dev.joaoabreu.portfolioapi.recommendations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class RecommendationModels {
    private RecommendationModels() {}
    public enum Category { BOOKS, COURSES, VIDEOS, SETUP, TOOLS }
    public enum Status { DRAFT, ARCHIVED }
    public record Copy(@NotNull @Size(max = 150) String title,
            @NotNull @Size(max = 3000) String description,
            @NotNull @Size(max = 200) String imageAlt) {
        boolean complete() { return !title.isBlank() && !description.isBlank() && !imageAlt.isBlank(); }
    }
    public record Details(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
            @NotNull Category category,
            @NotNull @Pattern(regexp = "(?:|[0-9a-f]{64}\\.png)") String imageKey,
            @NotNull @Size(max = 1000) String url,
            @Min(0) int displayOrder,
            @NotNull @Valid Copy en, @NotNull @Valid Copy pt,
            @Pattern(regexp = "(?:|[A-Za-z0-9_-]{1,100})") String googleBooksId) {}
    public record Save(@NotNull @Min(0) Long version, @NotNull @Valid Details data) {}
    public record Version(@NotNull @Min(0) Long version) {}
    public record ChangeStatus(@NotNull @Min(0) Long version, @NotNull Status status) {}
    public record Draft(long id, long version, Status status, Details data, Instant updatedAt) {}
    public record Publication(long recommendationId, String revision, long draftVersion, Details data) {}
    public record Snapshot(int schemaVersion, List<Publication> recommendations) {}
    public record Summary(long recommendationId, String revision, long draftVersion) {}
}
