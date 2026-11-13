package dev.joaoabreu.portfolioapi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM recommendation_publications").update();
        jdbc.sql("DELETE FROM recommendation_drafts").update();
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", png);
        String result = mvc.perform(multipart("/api/admin/recommendation-images")
                .file(new MockMultipartFile("image", "cover.png", "image/png", png.toByteArray())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        key = json.readTree(result).get("key").asText();
    }

    @Test
    void selectedCopiesAndImagesStayIndependentOfDraftEditsAndArchive() throws Exception {
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous())).andExpect(status().isNotFound());
        long id = create("sample", true);
        select(id, 0, 200);
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(put("/api/admin/recommendations/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("edited", 0, true, key, ""))).andExpect(status().isOk());
        select(id, 0, 409);
        mvc.perform(get("/api/recommendations").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[0].data.slug").value("sample"))
                .andExpect(jsonPath("$.recommendations[0].draftVersion").value(0))
                .andExpect(jsonPath("$.recommendations[0].status").doesNotExist());
        mvc.perform(patch("/api/admin/recommendations/" + id + "/status").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        select(id, 2, 400);
        mvc.perform(get("/api/recommendations")).andExpect(jsonPath("$.recommendations.length()").value(1));
        mvc.perform(delete("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/recommendations/" + id)).andExpect(status().isOk());
    }

    @Test
    void publishingRequiresBothCopiesAndImageAndDuplicateSlugsConflict() throws Exception {
        long id = create("incomplete", false);
        select(id, 0, 400);
        mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("incomplete", 0, true, key, ""))).andExpect(status().isConflict());
        String withoutImage = mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("no-image", 0, true, "", ""))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        select(json.readTree(withoutImage).get("id").asLong(), 0, 400);
        for (String url : new String[] { "javascript:alert(1)", "http://example.com", "https://user:password@example.com" })
            mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(payload("unsafe", 0, true, key, url))).andExpect(status().isBadRequest());
    }

    @Test
    void privateDataAndUploadsRequireAdminAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get("/api/admin/recommendations").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/recommendation-images/" + key).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/recommendations").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/recommendations").contentType(MediaType.APPLICATION_JSON)
                .content(payload("no-csrf", 0, true, key, ""))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/recommendation-images").file(new MockMultipartFile("image", new byte[] {1, 2})))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/recommendation-images").file(new MockMultipartFile("image", "evil.svg", "image/svg+xml", "<svg/>".getBytes())).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleEditsCannotOverwriteChangesAndNullVersionIsRejected() throws Exception {
        long id = create("original", true);
        String endpoint = "/api/admin/recommendations/" + id;
        mvc.perform(put(endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload("new", 0, true, key, "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload("stale", 0, true, key, "")))
                .andExpect(status().isConflict());
        mvc.perform(put(endpoint + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(endpoint)).andExpect(jsonPath("$.data.slug").value("new"));
    }

    @Test
    void simultaneousEditsAcceptOnlyOneVersion() throws Exception {
        long id = create("concurrent", true);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 2; index++) {
                String body = payload("concurrent-" + index, 0, true, key, "");
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return mvc.perform(put("/api/admin/recommendations/" + id).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            statuses.sort(Integer::compare);
            assertEquals(java.util.List.of(200, 409), statuses);
        }
        mvc.perform(get("/api/admin/recommendations/" + id)).andExpect(jsonPath("$.version").value(1));
    }

    private long create(String slug, boolean complete) throws Exception {
        String result = mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload(slug, 0, complete, key, ""))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("id").asLong();
    }
    private void select(long id, long version, int status) throws Exception {
        mvc.perform(put("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", version)))).andExpect(status().is(status));
    }
    private String payload(String slug, long version, boolean complete, String image, String url) {
        var copy = Map.of("title", "Synthetic book", "description", "Test recommendation", "imageAlt", "Synthetic cover");
        var pt = complete ? copy : Map.of("title", "", "description", "", "imageAlt", "");
        return json.writeValueAsString(Map.of("version", version, "data", Map.of("slug", slug, "category", "BOOKS",
                "imageKey", image, "url", url, "displayOrder", 0, "en", copy, "pt", pt)));
    }
}
