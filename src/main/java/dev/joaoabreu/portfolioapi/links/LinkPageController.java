package dev.joaoabreu.portfolioapi.links;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

@RestController
public class LinkPageController {
    private final LinkPageService links;

    public LinkPageController(LinkPageService links) { this.links = links; }

    @GetMapping("/api/link-page")
    ResponseEntity<LinkPage> published() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(links.published());
    }

    @GetMapping("/api/admin/link-page")
    ResponseEntity<LinkPageService.View> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(links.view());
    }

    @PutMapping("/api/admin/link-page/draft")
    LinkPageService.View saveDraft(@Valid @RequestBody DraftRequest request) {
        return links.saveDraft(request.version(), request.page());
    }

    @PostMapping("/api/admin/link-page/publish")
    LinkPageService.View publish(@Valid @RequestBody PublishRequest request) {
        return links.publish(request.version());
    }

    @ExceptionHandler(LinkPage.Invalid.class)
    ProblemDetail invalid(LinkPage.Invalid exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setProperty("field", exception.field);
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail conflict() { return ProblemDetail.forStatus(HttpStatus.CONFLICT); }

    public record DraftRequest(@Min(0) long version, @NotNull LinkPage page) {}
    public record PublishRequest(@Min(0) long version) {}
}
