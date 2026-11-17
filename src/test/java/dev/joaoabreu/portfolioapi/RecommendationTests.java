package dev.joaoabreu.portfolioapi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github" })
@WithMockUser(roles = "ADMIN")
class RecommendationTests {
    static Path media;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        media = Files.createTempDirectory("recommendation-tests-");
        registry.add("portfolio.recommendations.media-dir", media::toString);
    }
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;
    String key;
    long books;
    long setup;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM recommendation_publications").update();
        jdbc.sql("DELETE FROM recommendation_drafts").update();
        jdbc.sql("DELETE FROM recommendation_sections").update();
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", png);
        String result = mvc.perform(multipart("/api/admin/recommendation-images")
                .file(new MockMultipartFile("image", "cover.png", "image/png", png.toByteArray())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        key = json.readTree(result).get("key").asString();
        books = section("BOOKS", "Books", "Livros");
        setup = section("ITEMS", "Setup", "Setup");
    }

    @Test
    void selectedCopiesAndImagesStayIndependentOfDraftEditsAndArchive() throws Exception {
        long before = jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single();
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous())).andExpect(status().isNotFound());
        long id = create(book("sample", true, key, ""));
        select(id, 0, 200);
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(put("/api/admin/recommendations/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, book("edited", true, key, "")))).andExpect(status().isOk());
        select(id, 0, 409);
        mvc.perform(get("/api/recommendations").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(3))
                .andExpect(jsonPath("$.sections[0].kind").value("BOOKS"))
                .andExpect(jsonPath("$.sections[0].slug").value("books"))
                .andExpect(jsonPath("$.sections[0].pt.title").value("Livros"))
                .andExpect(jsonPath("$.sections[0].items[0].data.slug").value("sample"))
                .andExpect(jsonPath("$.sections[0].items[0].data.rating").value(4))
                .andExpect(jsonPath("$.sections[0].items[0].draftVersion").value(0))
                .andExpect(jsonPath("$.sections[0].items[0].status").doesNotExist());
        mvc.perform(patch("/api/admin/recommendations/" + id + "/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        select(id, 2, 400);
        assertEquals(before + 1, jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single());
        mvc.perform(delete("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(get("/api/recommendations")).andExpect(jsonPath("$.sections.length()").value(0));
        assertEquals(before + 2, jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single());
    }

    @Test
    void booksNeedRatingGroupAndCoverWhileItemsNeedNeither() throws Exception {
        var noRating = book("no-rating", true, key, "");
        noRating.put("rating", 0);
        var noGroup = book("no-group", true, key, "");
        noGroup.put("bookGroup", null);
        var rated = item("rated-item", "");
        rated.put("rating", 3);
        var grouped = item("grouped-item", "");
        grouped.put("bookGroup", "TECHNICAL");
        for (var invalid : List.of(noRating, noGroup, rated, grouped))
            mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(0, invalid))).andExpect(status().isBadRequest());
        select(create(book("coverless", true, "", "")), 0, 400);
        select(create(item("keyboard", "")), 0, 200);
        mvc.perform(get("/api/recommendations")).andExpect(jsonPath("$.sections[0].kind").value("ITEMS"))
                .andExpect(jsonPath("$.sections[0].items[0].data.subtitle").value("Synthetic brand"))
                .andExpect(jsonPath("$.sections[0].items[0].data.en.imageAlt").value("Photo of Synthetic keyboard"));
    }

    @Test
    void booksTakeHalfStarsAndAnOptionalReadingLanguage() throws Exception {
        var half = book("half", true, key, "");
        half.put("rating", 4.5);
        half.put("readIn", "EN");
        select(create(half), 0, 200);
        mvc.perform(get("/api/recommendations")).andExpect(jsonPath("$.sections[0].items[0].data.rating").value(4.5))
                .andExpect(jsonPath("$.sections[0].items[0].data.readIn").value("EN"));
        var odd = book("odd", true, key, "");
        odd.put("rating", 4.3);
        var tooHigh = book("high", true, key, "");
        tooHigh.put("rating", 5.5);
        var itemLanguage = item("item-language", "");
        itemLanguage.put("readIn", "PT");
        var badLanguage = book("bad-language", true, key, "");
        badLanguage.put("readIn", "ES");
        for (var invalid : List.of(odd, tooHigh, itemLanguage, badLanguage))
            mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(0, invalid))).andExpect(status().isBadRequest());
    }

    @Test
    void externalLinkSectionsAppearWithoutItemsAndRefuseThem() throws Exception {
        long before = jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single();
        mvc.perform(post("/api/admin/recommendation-sections").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, "LINK", "Gear", "Equipamentos", "http://example.com/list"))).andExpect(status().isBadRequest());
        String created = mvc.perform(post("/api/admin/recommendation-sections").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, "LINK", "My gear", "Meu setup", "https://www.amazon.com.br/shop/example/list/1")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.slug").value("my-gear"))
                .andReturn().getResponse().getContentAsString();
        long link = json.readTree(created).get("id").asLong();
        mvc.perform(get("/api/recommendations")).andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].kind").value("LINK"))
                .andExpect(jsonPath("$.sections[0].url").value("https://www.amazon.com.br/shop/example/list/1"))
                .andExpect(jsonPath("$.sections[0].pt.title").value("Meu setup"));
        var orphan = item("orphan", "");
        orphan.put("sectionId", link);
        mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, orphan))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/recommendation-sections").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, "ITEMS", "Setup", "Setup", "https://example.com"))).andExpect(status().isBadRequest());
        assertTrue(jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single() >= before);
    }

    @Test
    void sectionAddressesAreGeneratedUniqueAndExplicitDuplicatesConflict() throws Exception {
        long again = section("ITEMS", "Books", "Outros livros");
        mvc.perform(get("/api/admin/recommendation-sections")).andExpect(jsonPath("$[?(@.id == " + again + ")].data.slug").value("books-2"));
        var body = new HashMap<String, Object>(Map.of("version", 0, "data", Map.of("kind", "ITEMS",
                "en", Map.of("title", "Tools"), "pt", Map.of("title", "Ferramentas"), "slug", "books")));
        mvc.perform(post("/api/admin/recommendation-sections").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isConflict());
    }

    @Test
    void blankSlugsAreGeneratedFromTheTitleAndKeptUnique() throws Exception {
        long first = create(book("", true, key, ""));
        long second = create(book("", true, key, ""));
        mvc.perform(get("/api/admin/recommendations/" + first)).andExpect(jsonPath("$.data.slug").value("synthetic-book"))
                .andExpect(jsonPath("$.data.en.imageAlt").value("Cover of Synthetic book"))
                .andExpect(jsonPath("$.data.pt.imageAlt").value("Capa de Livro sintético"));
        mvc.perform(get("/api/admin/recommendations/" + second)).andExpect(jsonPath("$.data.slug").value("synthetic-book-2"));
    }

    @Test
    void sectionsAndItemsAreOrderedByDraggingAndTheSnapshotFollows() throws Exception {
        long a = create(book("a", true, key, "")), b = create(book("b", true, key, "")), c = create(item("c", ""));
        for (long id : new long[] { a, b, c }) select(id, 0, 200);
        long before = jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single();
        mvc.perform(put("/api/admin/recommendations/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sectionId", books, "ids", List.of(b, a))))).andExpect(status().isOk());
        mvc.perform(put("/api/admin/recommendation-sections/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", List.of(setup, books))))).andExpect(status().isOk());
        mvc.perform(get("/api/recommendations"))
                .andExpect(jsonPath("$.sections[0].id").value(setup))
                .andExpect(jsonPath("$.sections[1].items[0].data.slug").value("b"))
                .andExpect(jsonPath("$.sections[1].items[1].data.slug").value("a"));
        assertEquals(before + 2, jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single());
        mvc.perform(put("/api/admin/recommendations/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sectionId", books, "ids", List.of(a))))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/recommendation-sections/order").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", List.of(books, books))))).andExpect(status().isBadRequest());
    }

    @Test
    void sectionsCanBeRenamedButNotDeletedOrRetypedWhileTheyHoldItems() throws Exception {
        create(book("kept", true, key, ""));
        mvc.perform(put("/api/admin/recommendation-sections/" + books).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, "BOOKS", "Reading", "Leitura"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pt.title").value("Leitura")).andExpect(jsonPath("$.items").value(1));
        mvc.perform(put("/api/admin/recommendation-sections/" + books).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(1, "ITEMS", "Reading", "Leitura"))).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/recommendation-sections/" + books).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/recommendation-sections/" + setup).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/recommendation-sections").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, "ITEMS", " ", "Vazio"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/recommendation-sections")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unsafeLinksDuplicateSlugsAndUnknownSectionsAreRejected() throws Exception {
        create(book("taken", true, key, ""));
        mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, book("taken", true, key, "")))).andExpect(status().isConflict());
        for (String url : new String[] { "javascript:alert(1)", "http://example.com", "https://user:password@example.com" })
            mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(body(0, book("unsafe", true, key, url)))).andExpect(status().isBadRequest());
        var orphan = book("orphan", true, key, "");
        orphan.put("sectionId", 999999);
        mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, orphan))).andExpect(status().isBadRequest());
        select(create(book("incomplete", false, key, "")), 0, 400);
    }

    @Test
    void privateDataAndUploadsRequireAdminAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get("/api/admin/recommendations").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/recommendation-sections").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/recommendation-images/" + key).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/recommendations").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/recommendations").contentType(MediaType.APPLICATION_JSON)
                .content(body(0, book("no-csrf", true, key, "")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/recommendation-sections").contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, "ITEMS", "No", "Não"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/recommendation-images").file(new MockMultipartFile("image", new byte[] {1, 2})))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/recommendation-images").file(new MockMultipartFile("image", "evil.svg", "image/svg+xml", "<svg/>".getBytes())).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleEditsCannotOverwriteChangesAndNullVersionIsRejected() throws Exception {
        long id = create(book("original", true, key, ""));
        String endpoint = "/api/admin/recommendations/" + id;
        mvc.perform(put(endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(0, book("new", true, key, ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(0, book("stale", true, key, ""))))
                .andExpect(status().isConflict());
        mvc.perform(put(endpoint + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(endpoint)).andExpect(jsonPath("$.data.slug").value("new"));
    }

    @Test
    void simultaneousEditsAcceptOnlyOneVersion() throws Exception {
        long id = create(book("concurrent", true, key, ""));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 2; index++) {
                String content = body(0, book("concurrent-" + index, true, key, ""));
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return mvc.perform(put("/api/admin/recommendations/" + id).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            statuses.sort(Integer::compare);
            assertEquals(List.of(200, 409), statuses);
        }
        mvc.perform(get("/api/admin/recommendations/" + id)).andExpect(jsonPath("$.version").value(1));
    }

    private long section(String kind, String en, String pt) throws Exception {
        String result = mvc.perform(post("/api/admin/recommendation-sections").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(sectionBody(0, kind, en, pt))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("id").asLong();
    }
    private String sectionBody(long version, String kind, String en, String pt) {
        return sectionBody(version, kind, en, pt, "");
    }
    private String sectionBody(long version, String kind, String en, String pt, String url) {
        return json.writeValueAsString(Map.of("version", version, "data", Map.of("kind", kind,
                "en", Map.of("title", en, "description", "Short " + en), "pt", Map.of("title", pt, "description", "Curta " + pt), "url", url)));
    }
    private long create(Map<String, Object> data) throws Exception {
        String result = mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, data))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("id").asLong();
    }
    private void select(long id, long version, int status) throws Exception {
        mvc.perform(put("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", version)))).andExpect(status().is(status));
    }
    private String body(long version, Map<String, Object> data) {
        var body = new HashMap<String, Object>();
        body.put("version", version);
        body.put("data", data);
        return json.writeValueAsString(body);
    }
    private Map<String, Object> book(String slug, boolean complete, String image, String url) {
        var data = new HashMap<String, Object>();
        data.put("slug", slug);
        data.put("sectionId", books);
        data.put("imageKey", image);
        data.put("url", url);
        data.put("rating", 4);
        data.put("bookGroup", "TECHNICAL");
        data.put("subtitle", "Synthetic author");
        data.put("en", Map.of("title", "Synthetic book", "description", "Test recommendation", "imageAlt", ""));
        data.put("pt", complete ? Map.of("title", "Livro sintético", "description", "Recomendação de teste", "imageAlt", "")
                : Map.of("title", "", "description", "", "imageAlt", ""));
        data.put("googleBooksId", "");
        return data;
    }
    private Map<String, Object> item(String slug, String image) {
        var data = book(slug, true, image, "https://example.com/keyboard");
        data.put("sectionId", setup);
        data.put("rating", 0);
        data.put("bookGroup", null);
        data.put("subtitle", "Synthetic brand");
        data.put("en", Map.of("title", "Synthetic keyboard", "description", "Test item", "imageAlt", ""));
        data.put("pt", Map.of("title", "Teclado sintético", "description", "Item de teste", "imageAlt", ""));
        return data;
    }
}
