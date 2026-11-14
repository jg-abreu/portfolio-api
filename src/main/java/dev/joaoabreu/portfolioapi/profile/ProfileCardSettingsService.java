package dev.joaoabreu.portfolioapi.profile;

import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ProfileCardSettingsService {
    private final ProfileCardStateRepository repository;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public ProfileCardSettingsService(ProfileCardStateRepository repository, ObjectMapper json, ApplicationEventPublisher events) {
        this.repository = repository;
        this.json = json;
        this.events = events;
    }

    public View view() { return view(state()); }

    public ProfileCardSettings published() {
        try {
            return repository.findById(ProfileCardState.ID).map(state -> parse(state.getPublished()))
                    .orElse(ProfileCardSettings.DEFAULTS);
        } catch (RuntimeException exception) {
            return ProfileCardSettings.DEFAULTS;
        }
    }

    @Transactional
    public View saveDraft(long version, ProfileCardSettings settings) {
        ProfileCardState state = current(version);
        state.saveDraft(json.writeValueAsString(settings.validated()), Instant.now(clock));
        return view(repository.saveAndFlush(state));
    }

    @Transactional
    public View publish(long version) {
        ProfileCardState state = current(version);
        state.publish(json.writeValueAsString(parse(state.getDraft())), Instant.now(clock));
        View view = view(repository.saveAndFlush(state));
        events.publishEvent(new Published());
        return view;
    }

    private ProfileCardState current(long version) {
        ProfileCardState state = state();
        if (state.getVersion() != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return state;
    }

    private ProfileCardState state() {
        return repository.findById(ProfileCardState.ID)
                .orElseThrow(() -> new IllegalStateException("missing profile card settings row"));
    }

    private View view(ProfileCardState state) {
        ProfileCardSettings draft = parse(state.getDraft());
        ProfileCardSettings published = state.getPublished() == null ? null : parse(state.getPublished());
        boolean pending = !draft.equals(published == null ? ProfileCardSettings.DEFAULTS : published);
        return new View(state.getVersion(), draft, published, ProfileCardSettings.DEFAULTS, pending,
                state.getPublishedAt(), state.getUpdatedAt());
    }

    private ProfileCardSettings parse(String stored) {
        if (stored == null) return ProfileCardSettings.DEFAULTS;
        try {
            return json.readValue(stored, ProfileCardSettings.class).validated();
        } catch (RuntimeException exception) {
            return ProfileCardSettings.DEFAULTS;
        }
    }

    public record View(long version, ProfileCardSettings draft, ProfileCardSettings published,
            ProfileCardSettings defaults, boolean pending, Instant publishedAt, Instant updatedAt) {}

    public record Published() {}
}
