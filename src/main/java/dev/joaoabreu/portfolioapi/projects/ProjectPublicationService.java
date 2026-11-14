package dev.joaoabreu.portfolioapi.projects;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import dev.joaoabreu.portfolioapi.publication.PublicationRequests;
import dev.joaoabreu.portfolioapi.recommendations.RecommendationImages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ProjectPublicationService {
    private final ProjectDraftRepository drafts;
    private final ProjectPublicationRepository publications;
    private final ObjectMapper json;
    private final PublicationRequests requests;
    private final RecommendationImages images;

    public ProjectPublicationService(ProjectDraftRepository drafts, ProjectPublicationRepository publications,
            ObjectMapper json, PublicationRequests requests, RecommendationImages images) {
        this.drafts = drafts;
        this.publications = publications;
        this.json = json;
        this.requests = requests;
        this.images = images;
    }

    public List<ProjectPublication.PublicationResponse> list() {
        return publications.findAll().stream().map(ProjectPublication::toResponse).toList();
    }

    public PublicSnapshot snapshot() {
        var projects = publications.findAll().stream()
                .map(publication -> json.readValue(publication.getPayload(), ProjectPublication.PublicProject.class))
                .sorted(Comparator.comparingInt(ProjectPublication.PublicProject::displayOrder)
                        .thenComparing(ProjectPublication.PublicProject::slug)).toList();
        return new PublicSnapshot(1, projects);
    }

    @Transactional
    public ProjectPublication.PublicationResponse select(long id, long version) {
        var draft = find(id, version).toResponse();
        boolean signal = draft.role() == ProjectDraft.Role.SIGNAL;
        boolean cover = !draft.coverKey().isEmpty();
        if (draft.status() != ProjectDraft.Status.DRAFT || draft.stack().isBlank()
                || !complete(draft.en(), signal) || !complete(draft.pt(), signal)
                || cover && (signal || draft.en().coverAlt().isBlank() || draft.pt().coverAlt().isBlank() || !images.exists(draft.coverKey()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String revision = UUID.randomUUID().toString();
        var project = new ProjectPublication.PublicProject(id, revision, draft.slug(), draft.repositoryUrl(),
                draft.liveUrl(), draft.stack(), draft.displayOrder(), draft.en(), draft.pt(),
                draft.role(), draft.inProgress(), draft.coverKey(), draft.profiles());
        var publication = new ProjectPublication(id, draft.slug(), revision, version, json.writeValueAsString(project), draft.coverKey());
        var response = publications.saveAndFlush(publication).toResponse();
        requests.changed();
        return response;
    }

    @Transactional
    public void withdraw(long id, long version) {
        find(id, version);
        publications.deleteById(id);
        publications.flush();
        requests.changed();
    }

    private ProjectDraft find(long id, long version) {
        var draft = drafts.findForPublication(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (draft.getVersion() != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return draft;
    }

    private boolean complete(ProjectCopy.CopyRequest copy, boolean signal) {
        if (copy.title().isBlank() || copy.tagline().isBlank()) return false;
        return signal || !copy.problem().isBlank() && !copy.decisions().isBlank() && !copy.result().isBlank();
    }

    public record PublicSnapshot(int schemaVersion, List<ProjectPublication.PublicProject> projects) {}
}
