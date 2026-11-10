package dev.joaoabreu.portfolioapi.projects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProjectDraftRequest(
        @Min(0) long version,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
        @NotBlank @Size(max = 300)
        @Pattern(regexp = "https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+/?") String repositoryUrl,
        @NotNull @Size(max = 500) @Pattern(regexp = "(?:|https://[^\\s]+)") String liveUrl,
        @NotNull @Size(max = 1000) String stack,
        @Min(0) int displayOrder,
        @NotNull @Valid ProjectCopy.CopyRequest en,
        @NotNull @Valid ProjectCopy.CopyRequest pt) {}