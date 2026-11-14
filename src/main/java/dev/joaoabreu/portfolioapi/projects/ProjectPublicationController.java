package dev.joaoabreu.portfolioapi.projects;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProjectPublicationController {
    private final ProjectPublicationService service;

    public ProjectPublicationController(ProjectPublicationService service) { this.service = service; }

    @GetMapping("/api/projects")
    ProjectPublicationService.PublicSnapshot snapshot() { return service.snapshot(); }

    @GetMapping("/api/admin/project-publications")
    List<ProjectPublication.PublicationResponse> list() { return service.list(); }

    @PutMapping("/api/admin/projects/{id}/publication")
    ProjectPublication.PublicationResponse select(@PathVariable long id, @Valid @RequestBody VersionRequest request) {
        return service.select(id, request.version());
    }

    @DeleteMapping("/api/admin/projects/{id}/publication")
    ResponseEntity<Void> withdraw(@PathVariable long id, @Valid @RequestBody VersionRequest request) {
        service.withdraw(id, request.version());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception) { return ProblemDetail.forStatus(HttpStatus.CONFLICT); }

    public record VersionRequest(@Min(0) long version) {}
}
