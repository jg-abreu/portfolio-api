package dev.joaoabreu.portfolioapi.projects;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Embeddable
public class ProjectCopy {
    private String title;
    private String tagline;
    private String problem;
    private String decisions;
    private String result;
    private String body;

    protected ProjectCopy() {}

    public ProjectCopy(CopyRequest copy) {
        title = copy.title();
        tagline = copy.tagline();
        problem = copy.problem();
        decisions = copy.decisions();
        result = copy.result();
        body = copy.body();
    }

    public CopyRequest toResponse() {
        return new CopyRequest(title, tagline, problem, decisions, result, body);
    }

    public record CopyRequest(
            @NotBlank @Size(max = 150) String title,
            @NotNull @Size(max = 300) String tagline,
            @NotNull @Size(max = 5000) String problem,
            @NotNull @Size(max = 10000) String decisions,
            @NotNull @Size(max = 5000) String result,
            @NotNull @Size(max = 50000) String body) {}
}