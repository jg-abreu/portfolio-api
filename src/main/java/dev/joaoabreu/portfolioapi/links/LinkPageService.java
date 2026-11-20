package dev.joaoabreu.portfolioapi.links;

import java.time.Clock;
import java.time.Instant;
import dev.joaoabreu.portfolioapi.publication.PublicationRequests;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class LinkPageService {
    private final LinkPageStateRepository repository;
    private final ObjectMapper json;
    private final PublicationRequests requests;
    private final Clock clock = Clock.systemUTC();

    public LinkPageService(LinkPageStateRepository repository, ObjectMapper json, PublicationRequests requests) {
        this.repository = repository;
        this.json = json;
        this.requests = requests;
    }

    public View view() { return view(state()); }

    public LinkPage published() {
        return repository.findById(LinkPageState.ID).map(state -> parse(state.getPublished())).orElse(LinkPage.DEFAULTS);
    }

    @Transactional
    public View saveDraft(long version, LinkPage page) {
        LinkPageState state = current(version);
        state.saveDraft(json.writeValueAsString(page.validated()), Instant.now(clock));
        return view(repository.saveAndFlush(state));
    }

    @Transactional
    public View publish(long version) {
        LinkPageState state = current(version);
        state.publish(json.writeValueAsString(parse(state.getDraft())), Instant.now(clock));
        View view = view(repository.saveAndFlush(state));
        requests.changed();
        return view;
    }

    private LinkPageState current(long version) {
        LinkPageState state = state();
        if (state.getVersion() != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return state;
    }

    private LinkPageState state() {
        return repository.findById(LinkPageState.ID)
                .orElseThrow(() -> new IllegalStateException("missing link page settings row"));
    }

    private View view(LinkPageState state) {
        LinkPage draft = parse(state.getDraft());
        LinkPage published = state.getPublished() == null ? null : parse(state.getPublished());
        boolean pending = !draft.equals(published == null ? LinkPage.DEFAULTS : published);
        return new View(state.getVersion(), draft, published, LinkPage.DEFAULTS, pending,
                state.getPublishedAt(), state.getUpdatedAt());
    }

    private LinkPage parse(String stored) {
        if (stored == null) return LinkPage.DEFAULTS;
        try {
            return json.readValue(stored, LinkPage.class).validated();
        } catch (RuntimeException exception) {
            return LinkPage.DEFAULTS;
        }
    }

    public record View(long version, LinkPage draft, LinkPage published, LinkPage defaults, boolean pending,
            Instant publishedAt, Instant updatedAt) {}
}
