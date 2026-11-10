package dev.joaoabreu.portfolioapi.projects;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ProjectDraftService {
    private final ProjectDraftRepository repository;

    public ProjectDraftService(ProjectDraftRepository repository) { this.repository = repository; }

    public List<ProjectDraft.DraftResponse> list() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc().stream().map(ProjectDraft::toResponse).toList();
    }

    public ProjectDraft.DraftResponse get(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).toResponse();
    }

    @Transactional
    public ProjectDraft.DraftResponse create(ProjectDraftRequest request) {
        return repository.saveAndFlush(new ProjectDraft(request)).toResponse();
    }

    @Transactional
    public ProjectDraft.DraftResponse update(long id, ProjectDraftRequest request) {
        ProjectDraft draft = find(id, request.version());
        draft.update(request);
        return repository.saveAndFlush(draft).toResponse();
    }

    @Transactional
    public ProjectDraft.DraftResponse changeStatus(long id, ProjectDraft.Status status, long version) {
        ProjectDraft draft = find(id, version);
        draft.changeStatus(status);
        return repository.saveAndFlush(draft).toResponse();
    }

    private ProjectDraft find(long id, long version) {
        ProjectDraft draft = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (draft.getVersion() != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return draft;
    }
}