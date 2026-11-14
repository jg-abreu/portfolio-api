package dev.joaoabreu.portfolioapi.projects;

import java.net.URI;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/projects")
public class ProjectDraftController {
    private final ProjectDraftService service;

    public ProjectDraftController(ProjectDraftService service) { this.service = service; }

    @GetMapping
    List<ProjectDraft.DraftResponse> list() { return service.list(); }

    @GetMapping("/{id}")
    ProjectDraft.DraftResponse get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    ResponseEntity<ProjectDraft.DraftResponse> create(@Valid @RequestBody ProjectDraftRequest request) {
        ProjectDraft.DraftResponse draft = service.create(request);
        return ResponseEntity.created(URI.create("/api/admin/projects/" + draft.id())).body(draft);
    }

    @PutMapping("/{id}")
    ProjectDraft.DraftResponse update(@PathVariable long id, @Valid @RequestBody ProjectDraftRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    ProjectDraft.DraftResponse changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request.status(), request.version());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail conflict(RuntimeException exception) { return ProblemDetail.forStatus(HttpStatus.CONFLICT); }

    public record StatusRequest(@NotNull ProjectDraft.Status status, @Min(0) long version) {}
}