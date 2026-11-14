package dev.joaoabreu.portfolioapi.projects;

import java.util.List;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_publications")
public class ProjectPublication {
    @Id
    private Long projectId;
    private String slug;
    private String revision;
    private long draftVersion;
    private String payload;
    private String coverKey = "";

    protected ProjectPublication() {}

    public ProjectPublication(long projectId, String slug, String revision, long draftVersion, String payload, String coverKey) {
        this.projectId = projectId;
        this.slug = slug;
        this.revision = revision;
        this.draftVersion = draftVersion;
        this.payload = payload;
        this.coverKey = coverKey;
    }

    public String getPayload() { return payload; }
    public PublicationResponse toResponse() { return new PublicationResponse(projectId, revision, draftVersion); }

    public record PublicationResponse(long projectId, String revision, long draftVersion) {}
    public record PublicProject(long projectId, String revision, String slug, String repositoryUrl,
            String liveUrl, String stack, int displayOrder,
            ProjectCopy.CopyRequest en, ProjectCopy.CopyRequest pt,
            ProjectDraft.Role role, boolean inProgress, String coverKey, List<ProjectDraftRequest.Profile> profiles) {}
}
