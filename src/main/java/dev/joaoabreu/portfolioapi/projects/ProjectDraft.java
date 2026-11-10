package dev.joaoabreu.portfolioapi.projects;

import java.time.Instant;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "project_drafts")
public class ProjectDraft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private long version;
    private String slug;
    private String repositoryUrl;
    private String liveUrl;
    private String stack;
    private int displayOrder;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title", column = @Column(name = "en_title")),
            @AttributeOverride(name = "tagline", column = @Column(name = "en_tagline")),
            @AttributeOverride(name = "problem", column = @Column(name = "en_problem")),
            @AttributeOverride(name = "decisions", column = @Column(name = "en_decisions")),
            @AttributeOverride(name = "result", column = @Column(name = "en_result")),
            @AttributeOverride(name = "body", column = @Column(name = "en_body"))
    })
    private ProjectCopy en;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "title", column = @Column(name = "pt_title")),
            @AttributeOverride(name = "tagline", column = @Column(name = "pt_tagline")),
            @AttributeOverride(name = "problem", column = @Column(name = "pt_problem")),
            @AttributeOverride(name = "decisions", column = @Column(name = "pt_decisions")),
            @AttributeOverride(name = "result", column = @Column(name = "pt_result")),
            @AttributeOverride(name = "body", column = @Column(name = "pt_body"))
    })
    private ProjectCopy pt;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProjectDraft() {}

    public ProjectDraft(ProjectDraftRequest request) {
        status = Status.DRAFT;
        createdAt = Instant.now();
        update(request);
    }

    public void update(ProjectDraftRequest request) {
        slug = request.slug();
        repositoryUrl = request.repositoryUrl();
        liveUrl = request.liveUrl();
        stack = request.stack();
        displayOrder = request.displayOrder();
        en = new ProjectCopy(request.en());
        pt = new ProjectCopy(request.pt());
        updatedAt = Instant.now();
    }

    public void changeStatus(Status nextStatus) {
        status = nextStatus;
        updatedAt = Instant.now();
    }

    public long getVersion() { return version; }

    public DraftResponse toResponse() {
        return new DraftResponse(id, version, slug, repositoryUrl, liveUrl, stack, displayOrder,
                status, en.toResponse(), pt.toResponse(), createdAt, updatedAt);
    }

    public enum Status { DRAFT, ARCHIVED }

    public record DraftResponse(Long id, long version, String slug, String repositoryUrl,
            String liveUrl, String stack, int displayOrder, Status status,
            ProjectCopy.CopyRequest en, ProjectCopy.CopyRequest pt, Instant createdAt, Instant updatedAt) {}
}