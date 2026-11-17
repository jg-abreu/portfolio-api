package dev.joaoabreu.portfolioapi.recommendations;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import dev.joaoabreu.portfolioapi.publication.PublicationRequests;
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
    private static final int MAX_SECTIONS = 20, MAX_ITEMS = 500;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final PublicationRequests requests;
    private final RecommendationImages images;
    private final GoogleBooksService books;

    public RecommendationService(JdbcClient jdbc, ObjectMapper json, RecommendationImages images, GoogleBooksService books, PublicationRequests requests) {
        this.jdbc = jdbc;
        this.json = json;
        this.images = images;
        this.books = books;
        this.requests = requests;
    }

    public List<Section> sections() {
        return jdbc.sql("""
                SELECT s.*, (SELECT COUNT(*) FROM recommendation_drafts d WHERE d.section_id = s.id AND d.status = 'DRAFT') AS items
                FROM recommendation_sections s ORDER BY s.display_order, s.id""").query(this::section).list();
    }

    @Transactional
    public Section createSection(SaveSection request) {
        if (request.version() != 0) throw conflict();
        jdbc.sql("LOCK TABLE recommendation_sections IN SHARE ROW EXCLUSIVE MODE").update();
        if (jdbc.sql("SELECT COUNT(*) FROM recommendation_sections").query(Long.class).single() >= MAX_SECTIONS)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Section limit reached");
        SectionData data = section(request.data(), null);
        long id = jdbc.sql("""
                INSERT INTO recommendation_sections(kind, en_title, pt_title, en_description, pt_description, slug, url, display_order)
                VALUES (:kind, :en, :pt, :enDescription, :ptDescription, :slug, :url,
                (SELECT COALESCE(MAX(display_order) + 1, 0) FROM recommendation_sections)) RETURNING id""")
                .param("kind", data.kind().name()).param("en", data.en().title()).param("pt", data.pt().title())
                .param("enDescription", data.en().description()).param("ptDescription", data.pt().description())
                .param("slug", data.slug()).param("url", data.url())
                .query(Long.class).single();
        return findSection(id, false);
    }

    @Transactional
    public Section updateSection(long id, SaveSection request) {
        Section current = findSection(id, true);
        if (current.version() != request.version()) throw conflict();
        if (current.data().kind() != request.data().kind() && current.items() + archivedItems(id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Move or remove the items before changing the section type");
        SectionData data = section(request.data(), id);
        jdbc.sql("""
                UPDATE recommendation_sections SET kind=:kind, en_title=:en, pt_title=:pt, en_description=:enDescription,
                pt_description=:ptDescription, slug=:slug, url=:url, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=:id""")
                .param("kind", data.kind().name()).param("en", data.en().title()).param("pt", data.pt().title())
                .param("enDescription", data.en().description()).param("ptDescription", data.pt().description())
                .param("slug", data.slug()).param("url", data.url()).param("id", id).update();
        if (hasPublications(id) || data.kind() == SectionKind.LINK || current.data().kind() == SectionKind.LINK) requests.changed();
        return findSection(id, false);
    }

    @Transactional
    public void deleteSection(long id, long version) {
        Section current = findSection(id, true);
        if (current.version() != version) throw conflict();
        if (current.items() + archivedItems(id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Remove the items before deleting the section");
        jdbc.sql("DELETE FROM recommendation_sections WHERE id=:id").param("id", id).update();
        if (current.data().kind() == SectionKind.LINK) requests.changed();
    }

    private SectionData section(SectionData data, Long id) {
        boolean link = data.kind() == SectionKind.LINK;
        if (link && (data.url().isEmpty() || !safeLink(data.url())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External sections need a credential-free HTTPS link");
        if (!link && !data.url().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only external sections have a link");
        String base = data.slug().isEmpty() ? slugify(data.en().title()) : data.slug();
        if (base.length() > 60) base = base.substring(0, 60).replaceAll("-+$", "");
        String slug = base;
        for (int suffix = 2; jdbc.sql("SELECT COUNT(*) FROM recommendation_sections WHERE slug=:slug AND id <> :id")
                .param("slug", slug).param("id", id == null ? -1 : id).query(Long.class).single() > 0; suffix++) {
            if (!data.slug().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Section address already in use");
            slug = base + "-" + suffix;
        }
        return new SectionData(data.kind(), data.en(), data.pt(), slug, data.url());
    }

    @Transactional
    public List<Section> orderSections(SectionOrder order) {
        jdbc.sql("SELECT id FROM recommendation_sections FOR UPDATE").query(Long.class).list();
        List<Long> existing = jdbc.sql("SELECT id FROM recommendation_sections").query(Long.class).list();
        if (!sameIds(order.ids(), existing)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "List every section exactly once");
        for (int index = 0; index < order.ids().size(); index++)
            jdbc.sql("UPDATE recommendation_sections SET display_order=:order WHERE id=:id")
                    .param("order", index).param("id", order.ids().get(index)).update();
        if (jdbc.sql("SELECT COUNT(*) FROM recommendation_publications").query(Long.class).single() > 0
                || existing.size() > 0 && jdbc.sql("SELECT COUNT(*) FROM recommendation_sections WHERE kind='LINK'").query(Long.class).single() > 0)
            requests.changed();
        return sections();
    }

    public List<Draft> list() {
        return jdbc.sql("SELECT * FROM recommendation_drafts ORDER BY section_id, display_order, id LIMIT 500")
                .query(this::draft).list();
    }

    public Draft get(long id) { return find(id, false); }

    @Transactional
    public Draft create(Save request) {
        if (request.version() != 0) throw conflict();
        jdbc.sql("LOCK TABLE recommendation_drafts IN SHARE ROW EXCLUSIVE MODE").update();
        if (jdbc.sql("SELECT COUNT(*) FROM recommendation_drafts").query(Long.class).single() >= MAX_ITEMS)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recommendation limit reached");
        Details data = normalize(request.data(), null);
        long id = jdbc.sql("""
                INSERT INTO recommendation_drafts(slug, payload, section_id, display_order)
                VALUES (:slug, :payload, :section, (SELECT COALESCE(MAX(display_order) + 1, 0) FROM recommendation_drafts WHERE section_id = :section))
                RETURNING id""")
                .param("slug", data.slug()).param("payload", json.writeValueAsString(data)).param("section", data.sectionId())
                .query(Long.class).single();
        return get(id);
    }

    @Transactional
    public Draft update(long id, Save request) {
        Draft current = find(id, true);
        checkVersion(current, request.version());
        Details data = normalize(request.data(), id);
        boolean moved = !data.sectionId().equals(current.data().sectionId());
        jdbc.sql("""
                UPDATE recommendation_drafts SET slug=:slug, payload=:payload, section_id=:section,
                display_order = CASE WHEN :moved THEN (SELECT COALESCE(MAX(display_order) + 1, 0) FROM recommendation_drafts WHERE section_id = :section) ELSE display_order END,
                version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=:id""")
                .param("slug", data.slug()).param("payload", json.writeValueAsString(data)).param("section", data.sectionId())
                .param("moved", moved).param("id", id).update();
        if (moved && isPublished(id)) requests.changed();
        return get(id);
    }

    @Transactional
    public List<Draft> orderItems(ItemOrder order) {
        findSection(order.sectionId(), true);
        List<Long> existing = jdbc.sql("SELECT id FROM recommendation_drafts WHERE section_id=:section FOR UPDATE")
                .param("section", order.sectionId()).query(Long.class).list();
        if (!sameIds(order.ids(), existing)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "List every item of the section exactly once");
        for (int index = 0; index < order.ids().size(); index++)
            jdbc.sql("UPDATE recommendation_drafts SET display_order=:order WHERE id=:id")
                    .param("order", index).param("id", order.ids().get(index)).update();
        if (hasPublications(order.sectionId())) requests.changed();
        return list();
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
        SectionKind kind = findSection(data.sectionId(), false).data().kind();
        boolean image = hasBook(data) || images.exists(data.imageKey());
        if (current.status() != Status.DRAFT || !data.en().complete() || !data.pt().complete() || (kind == SectionKind.BOOKS && !image))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both translations are required, and books need a cover");
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
        requests.changed();
        return new Summary(id, revision, current.version());
    }

    @Transactional
    public void withdraw(long id, long version) {
        checkVersion(find(id, true), version);
        jdbc.sql("DELETE FROM recommendation_publications WHERE recommendation_id=:id").param("id", id).update();
        requests.changed();
    }

    public Snapshot snapshot() {
        Map<Long, List<Publication>> items = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT d.section_id, p.payload FROM recommendation_publications p
                JOIN recommendation_drafts d ON d.id = p.recommendation_id ORDER BY d.section_id, d.display_order, d.id""")
                .query((row, number) -> {
                    items.computeIfAbsent(row.getLong(1), key -> new ArrayList<>())
                            .add(json.readValue(row.getString(2), Publication.class));
                    return null;
                }).list();
        List<PublicSection> sections = new ArrayList<>();
        for (Section section : sections()) {
            List<Publication> published = items.getOrDefault(section.id(), List.of());
            if (section.data().kind() != SectionKind.LINK && published.isEmpty()) continue;
            sections.add(new PublicSection(section.id(), section.data().slug(), section.data().kind(), section.data().url(),
                    section.data().en(), section.data().pt(), List.copyOf(published)));
        }
        return new Snapshot(3, sections);
    }

    public List<Summary> summaries() {
        return jdbc.sql("SELECT recommendation_id, revision, draft_version FROM recommendation_publications ORDER BY recommendation_id")
                .query((row, number) -> new Summary(row.getLong(1), row.getString(2), row.getLong(3))).list();
    }

    public byte[] publicImage(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}\\.png") || jdbc.sql("SELECT (SELECT COUNT(*) FROM recommendation_publications WHERE image_key=:key) + (SELECT COUNT(*) FROM project_publications WHERE cover_key=:key)")
                .param("key", key).query(Long.class).single() == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return images.read(key);
    }

    private Details normalize(Details data, Long id) {
        Section section = findSection(data.sectionId(), false);
        if (section.data().kind() == SectionKind.LINK)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External link sections have no items");
        boolean book = section.data().kind() == SectionKind.BOOKS;
        boolean halfStep = Math.rint(data.rating() * 2) == data.rating() * 2;
        if (book && (data.rating() < 0.5 || data.rating() > 5 || !halfStep || data.bookGroup() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Books need a rating from 0.5 to 5 in half steps and a group");
        if (!book && (data.rating() != 0 || data.bookGroup() != null || data.readIn() != null || hasBook(data)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only books have ratings, groups, reading languages and Google Books references");
        if (hasBook(data) && !data.imageKey().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose either a book cover or an uploaded image");
        if (!data.url().isEmpty() && !safeLink(data.url()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link must use credential-free HTTPS");
        if (!data.imageKey().isEmpty() && !images.exists(data.imageKey()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the image before attaching it");
        Copy en = copy(data.en(), book ? "Cover of " : "Photo of ");
        Copy pt = copy(data.pt(), book ? "Capa de " : "Foto de ");
        String slug = data.slug().isEmpty() ? uniqueSlug(slugify(en.title().isBlank() ? pt.title() : en.title()), id) : data.slug();
        return new Details(slug, data.sectionId(), data.imageKey(), data.url(), data.rating(), data.bookGroup(), data.readIn(),
                data.subtitle().strip(), en, pt, data.googleBooksId() == null ? "" : data.googleBooksId());
    }

    private static boolean safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Copy copy(Copy copy, String prefix) {
        String title = copy.title().strip();
        String alt = copy.imageAlt().strip();
        if (alt.isEmpty() && !title.isEmpty()) alt = (prefix + title).substring(0, Math.min(200, prefix.length() + title.length()));
        return new Copy(title, copy.description().strip(), alt);
    }

    static String slugify(String value) {
        String ascii = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 90) slug = slug.substring(0, 90).replaceAll("-+$", "");
        return slug.isEmpty() ? "item" : slug;
    }

    private String uniqueSlug(String base, Long id) {
        String candidate = base;
        for (int suffix = 2; jdbc.sql("SELECT COUNT(*) FROM recommendation_drafts WHERE slug=:slug AND id <> :id")
                .param("slug", candidate).param("id", id == null ? -1 : id).query(Long.class).single() > 0; suffix++)
            candidate = base + "-" + suffix;
        return candidate;
    }

    private static boolean sameIds(List<Long> requested, List<Long> existing) {
        return requested.size() == existing.size() && new HashSet<>(requested).size() == requested.size()
                && new HashSet<>(requested).equals(new HashSet<>(existing));
    }

    private long archivedItems(long section) {
        return jdbc.sql("SELECT COUNT(*) FROM recommendation_drafts WHERE section_id=:section AND status='ARCHIVED'")
                .param("section", section).query(Long.class).single();
    }

    private boolean hasPublications(long section) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM recommendation_publications p JOIN recommendation_drafts d ON d.id = p.recommendation_id
                WHERE d.section_id = :section""").param("section", section).query(Long.class).single() > 0;
    }

    private boolean isPublished(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM recommendation_publications WHERE recommendation_id=:id")
                .param("id", id).query(Long.class).single() > 0;
    }

    private Section findSection(Long id, boolean lock) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a section");
        return jdbc.sql("""
                SELECT s.*, (SELECT COUNT(*) FROM recommendation_drafts d WHERE d.section_id = s.id AND d.status = 'DRAFT') AS items
                FROM recommendation_sections s WHERE s.id=:id""" + (lock ? " FOR UPDATE OF s" : ""))
                .param("id", id).query(this::section).optional()
                .orElseThrow(() -> new ResponseStatusException(lock ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST, "Unknown section"));
    }

    private Section section(ResultSet row, int number) throws SQLException {
        return new Section(row.getLong("id"), row.getLong("version"), row.getInt("display_order"),
                new SectionData(SectionKind.valueOf(row.getString("kind")),
                        new SectionTitle(row.getString("en_title"), row.getString("en_description")),
                        new SectionTitle(row.getString("pt_title"), row.getString("pt_description")),
                        row.getString("slug"), row.getString("url")), row.getLong("items"));
    }

    private Draft find(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM recommendation_drafts WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(this::draft).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Draft draft(ResultSet row, int number) throws SQLException {
        Details stored = json.readValue(row.getString("payload"), Details.class);
        Details data = new Details(stored.slug(), row.getLong("section_id"), stored.imageKey(), stored.url(), stored.rating(),
                stored.bookGroup(), stored.readIn(), stored.subtitle(), stored.en(), stored.pt(), stored.googleBooksId());
        return new Draft(row.getLong("id"), row.getLong("version"), Status.valueOf(row.getString("status")),
                row.getInt("display_order"), data, row.getTimestamp("updated_at").toInstant());
    }

    private boolean hasBook(Details data) { return data.googleBooksId() != null && !data.googleBooksId().isEmpty(); }

    private void checkVersion(Draft current, long version) { if (current.version() != version) throw conflict(); }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Reload the recommendation before retrying"); }
}
