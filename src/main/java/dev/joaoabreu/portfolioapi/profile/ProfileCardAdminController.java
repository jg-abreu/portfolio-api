package dev.joaoabreu.portfolioapi.profile;

import java.util.Base64;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/admin/profile-card")
public class ProfileCardAdminController {
    private static final int MAX_PREVIEW = 8192;
    private final ProfileCardSettingsService settings;
    private final ObjectProvider<ProfileCardCache> cache;
    private final ObjectMapper json;

    public ProfileCardAdminController(ProfileCardSettingsService settings, ObjectProvider<ProfileCardCache> cache, ObjectMapper json) {
        this.settings = settings;
        this.cache = cache;
        this.json = json;
    }

    @GetMapping
    ProfileCardSettingsService.View get() { return settings.view(); }

    @PutMapping("/draft")
    ProfileCardSettingsService.View saveDraft(@Valid @RequestBody DraftRequest request) {
        return settings.saveDraft(request.version(), request.settings());
    }

    @PostMapping("/publish")
    ProfileCardSettingsService.View publish(@Valid @RequestBody PublishRequest request) {
        return settings.publish(request.version());
    }

    @GetMapping(value = "/preview.svg", produces = "image/svg+xml")
    ResponseEntity<String> preview(@RequestParam String settings) {
        if (settings.length() > MAX_PREVIEW) throw new ProfileCardSettings.Invalid("settings", "too large");
        ProfileCardSettings decoded;
        try {
            decoded = json.readValue(Base64.getUrlDecoder().decode(settings), ProfileCardSettings.class);
        } catch (RuntimeException exception) {
            throw new ProfileCardSettings.Invalid("settings", "malformed");
        }
        ProfileCardCache current = cache.getIfAvailable();
        ProfileCardCache.Snapshot snapshot = current == null ? null : current.snapshot();
        var card = ProfileCardRenderer.render(decoded.validated(),
                snapshot == null ? null : snapshot.data(), snapshot == null ? null : snapshot.fetchedAt());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(card.svg());
    }

    @ExceptionHandler(ProfileCardSettings.Invalid.class)
    ProblemDetail invalid(ProfileCardSettings.Invalid exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setProperty("field", exception.field);
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail conflict() { return ProblemDetail.forStatus(HttpStatus.CONFLICT); }

    public record DraftRequest(@Min(0) long version, @NotNull ProfileCardSettings settings) {}
    public record PublishRequest(@Min(0) long version) {}
}
