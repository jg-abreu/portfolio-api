package dev.joaoabreu.portfolioapi.recommendations;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static dev.joaoabreu.portfolioapi.recommendations.RecommendationModels.*;

@Service
@Transactional(readOnly = true)
public class RecommendationService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RecommendationImages images;
    private final GoogleBooksService books;

    public RecommendationService(JdbcClient jdbc, ObjectMapper json, RecommendationImages images, GoogleBooksService books) {
        this.jdbc = jdbc;
        this.json = json;
        this.images = images;
        this.books = books;
    }

    public List<Draft> list() {
        return jdbc.sql("SELECT * FROM recommendation_drafts ORDER BY updated_at DESC, id DESC LIMIT 500")
                .query(this::draft).list();
    }

    public Draft get(long id) { return find(id, false); }

    @Transactional
    public Draft create(Save request) {
        if (request.version() != 0) throw conflict();
        validate(request.data());
        jdbc.sql("LOCK TABLE recommendation_drafts IN SHARE ROW EXCLUSIVE MODE").update();
        if (jdbc.sql("SELECT COUNT(*) FROM recommendation_drafts").query(Long.class).single() >= 500)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recommendation limit reached");
        long id = jdbc.sql("INSERT INTO recommendation_drafts(slug, payload) VALUES (:slug, :payload) RETURNING id")
                .param("slug", request.data().slug()).param("payload", json.writeValueAsString(request.data()))
                .query(Long.class).single();
        return get(id);
    }

    @Transactional
    public Draft update(long id, Save request) {
        Draft current = find(id, true);
        checkVersion(current, request.version());
        validate(request.data());
        jdbc.sql("UPDATE recommendation_drafts SET slug=:slug, payload=:payload, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("slug", request.data().slug()).param("payload", json.writeValueAsString(request.data())).param("id", id).update();
        return get(id);
    }

    @Transactional
    public Draft status(long id, ChangeStatus request) {
        Draft current = find(id, true);
        checkVersion(current, request.version());
        jdbc.sql("UPDATE recommendation_drafts SET status=:status, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("status", request.status().name()).param("id", id).update();
        return get(id);
    }

    @Transactional
    public Summary select(long id, long version) {
        Draft current = find(id, true);
        checkVersion(current, version);
        Details data = current.data();
        if (current.status() != Status.DRAFT || !data.en().complete() || !data.pt().complete() || (!hasBook(data) && !images.exists(data.imageKey())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both translations and an image are required");
        if (hasBook(data)) books.verify(data.googleBooksId());
        String revision = UUID.randomUUID().toString();
        var publication = new Publication(id, revision, current.version(), data);
        jdbc.sql("""
                INSERT INTO recommendation_publications(recommendation_id, slug, revision, draft_version, image_key, payload)
                VALUES (:id, :slug, CAST(:revision AS uuid), :version, :image, :payload)
                ON CONFLICT (recommendation_id) DO UPDATE SET slug=EXCLUDED.slug, revision=EXCLUDED.revision,
                draft_version=EXCLUDED.draft_version, image_key=EXCLUDED.image_key, payload=EXCLUDED.payload
                """)
                .param("id", id).param("slug", data.slug()).param("revision", revision).param("version", current.version())
                .param("image", data.imageKey()).param("payload", json.writeValueAsString(publication)).update();
        return new Summary(id, revision, current.version());
    }

    @Transactional
    public void withdraw(long id, long version) {
        checkVersion(find(id, true), version);
        jdbc.sql("DELETE FROM recommendation_publications WHERE recommendation_id=:id").param("id", id).update();
    }

    public Snapshot snapshot() {
        var items = jdbc.sql("SELECT payload FROM recommendation_publications").query(String.class).list().stream()
                .map(payload -> json.readValue(payload, Publication.class))
                .sorted(Comparator.comparing((Publication item) -> item.data().category())
                        .thenComparingInt(item -> item.data().displayOrder()).thenComparing(item -> item.data().slug())).toList();
        return new Snapshot(1, items);
    }

    public List<Summary> summaries() {
        return jdbc.sql("SELECT recommendation_id, revision, draft_version FROM recommendation_publications ORDER BY recommendation_id")
                .query((row, number) -> new Summary(row.getLong(1), row.getString(2), row.getLong(3))).list();
    }

    public byte[] publicImage(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}\\.png") || jdbc.sql("SELECT COUNT(*) FROM recommendation_publications WHERE image_key=:key")
                .param("key", key).query(Long.class).single() == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return images.read(key);
    }

    private Draft find(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM recommendation_drafts WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(this::draft).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Draft draft(ResultSet row, int number) throws SQLException {
        return new Draft(row.getLong("id"), row.getLong("version"), Status.valueOf(row.getString("status")),
                json.readValue(row.getString("payload"), Details.class), row.getTimestamp("updated_at").toInstant());
    }

    private boolean hasBook(Details data) { return data.googleBooksId() != null && !data.googleBooksId().isEmpty(); }

    private void validate(Details data) {
        if (hasBook(data) && (data.category() != Category.BOOKS || !data.imageKey().isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose either a book cover or an uploaded image");
        if (!data.url().isEmpty()) {
            try {
                URI uri = URI.create(data.url());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link must use credential-free HTTPS");
            }
        }
        if (!data.imageKey().isEmpty() && !images.exists(data.imageKey()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the image before attaching it");
    }

    private void checkVersion(Draft current, long version) { if (current.version() != version) throw conflict(); }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Reload the recommendation before retrying"); }
}
