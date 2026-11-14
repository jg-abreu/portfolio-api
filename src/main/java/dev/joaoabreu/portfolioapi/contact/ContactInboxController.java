package dev.joaoabreu.portfolioapi.contact;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/messages")
public class ContactInboxController {
    private final ContactInboxService inbox;

    public ContactInboxController(ContactInboxService inbox) { this.inbox = inbox; }

    @GetMapping
    ContactInboxService.InboxPage list(@RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return inbox.list(archived, page, size);
    }

    @GetMapping("/{id}")
    ContactMessage.Detail get(@PathVariable long id) { return inbox.get(id); }

    @PatchMapping("/{id}/read")
    ContactMessage.Detail read(@PathVariable long id, @Valid @RequestBody StateRequest request) {
        return inbox.change(id, request.version(), request.value(), false);
    }

    @PatchMapping("/{id}/archive")
    ContactMessage.Detail archive(@PathVariable long id, @Valid @RequestBody StateRequest request) {
        return inbox.change(id, request.version(), request.value(), true);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail conflict(ObjectOptimisticLockingFailureException exception) {
        return ProblemDetail.forStatus(HttpStatus.CONFLICT);
    }

    public record StateRequest(@NotNull Boolean value, @NotNull @Min(0) Long version) {}
}
