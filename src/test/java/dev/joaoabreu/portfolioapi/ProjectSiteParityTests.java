package dev.joaoabreu.portfolioapi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import dev.joaoabreu.portfolioapi.projects.ProjectDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "portfolio.github.client-id=test-client",
        "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "portfolio.recommendations.media-dir=target/test-project-covers"
})
@WithMockUser(roles = "ADMIN")
class ProjectSiteParityTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired ProjectDraftRepository drafts;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM recommendation_publications").update();
        drafts.deleteAllInBatch();
    }

    @Test
    void featuredProjectPublishesRoleProgressCoverAndProfilesAndServesItsCover() throws Exception {
        String key = uploadCover();
        Map<String, Object> body = project("featured-project", "FEATURED", true);
        body.put("inProgress", true);
        body.put("coverKey", key);
        body.put("profiles", List.of(Map.of("label", "LeetCode", "url", "https://leetcode.com/u/example/")));
        long id = create(body);
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous())).andExpect(status().isNotFound());
        select(id).andExpect(status().isOk());
        mvc.perform(get("/api/projects").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].role").value("FEATURED"))
                .andExpect(jsonPath("$.projects[0].inProgress").value(true))
                .andExpect(jsonPath("$.projects[0].coverKey").value(key))
                .andExpect(jsonPath("$.projects[0].profiles[0].label").value("LeetCode"))
                .andExpect(jsonPath("$.projects[0].en.coverAlt").value("English cover description"))
                .andExpect(jsonPath("$.projects[0].pt.coverAlt").value("Descrição da capa"));
        mvc.perform(get("/api/recommendation-images/" + key).with(anonymous()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void signalNeedsOnlyTitleTaglineAndStack() throws Exception {
        Map<String, Object> body = project("signal-project", "SIGNAL", false);
        long id = create(body);
        select(id).andExpect(status().isOk());
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$.projects[0].role").value("SIGNAL"));
    }

    @Test
    void coverNeedsDescriptionsInBothLanguagesAndAStoredImage() throws Exception {
        Map<String, Object> missingAlt = project("no-alt", "SECONDARY", true);
        missingAlt.put("coverKey", uploadCover());
        missingAlt.put("pt", copy("Título", true, ""));
        select(create(missingAlt)).andExpect(status().isBadRequest());

        Map<String, Object> missingImage = project("no-image", "SECONDARY", true);
        missingImage.put("coverKey", "a".repeat(64) + ".png");
        select(create(missingImage)).andExpect(status().isBadRequest());
    }

    @Test
    void invalidProfilesAndCoverKeysAreRejected() throws Exception {
        Map<String, Object> badProfile = project("bad-profile", "SIGNAL", false);
        badProfile.put("profiles", List.of(Map.of("label", "Site", "url", "http://insecure.example")));
        mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(badProfile))).andExpect(status().isBadRequest());
        Map<String, Object> badKey = project("bad-key", "SECONDARY", true);
        badKey.put("coverKey", "../secret.png");
        mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(badKey))).andExpect(status().isBadRequest());
    }

    @Test
    void draftsWithoutTheNewFieldsDefaultToASecondaryCard() throws Exception {
        Map<String, Object> body = project("legacy-client", "SECONDARY", true);
        body.remove("role");
        long id = create(body);
        mvc.perform(get("/api/admin/projects/" + id)).andExpect(jsonPath("$.role").value("SECONDARY"))
                .andExpect(jsonPath("$.inProgress").value(false)).andExpect(jsonPath("$.coverKey").value(""))
                .andExpect(jsonPath("$.profiles.length()").value(0));
    }

    private String uploadCover() throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "png", png);
        String response = mvc.perform(multipart("/api/admin/recommendation-images")
                        .file(new MockMultipartFile("image", "cover.png", "image/png", png.toByteArray())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("key").asString();
    }

    private long create(Map<String, Object> body) throws Exception {
        String response = mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asLong();
    }

    private ResultActions select(long id) throws Exception {
        return mvc.perform(put("/api/admin/projects/" + id + "/publication").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
    }

    private Map<String, Object> project(String slug, String role, boolean complete) {
        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);
        body.put("slug", slug);
        body.put("repositoryUrl", "https://github.com/jg-abreu/" + slug);
        body.put("liveUrl", "");
        body.put("stack", "Java, Spring Boot");
        body.put("displayOrder", 0);
        body.put("role", role);
        body.put("en", copy("English title", complete, "English cover description"));
        body.put("pt", copy("Título em português", complete, "Descrição da capa"));
        return body;
    }

    private Map<String, String> copy(String title, boolean complete, String coverAlt) {
        String text = complete ? "Project details" : "";
        return Map.of("title", title, "tagline", "A short tagline", "problem", text, "decisions", text,
                "result", text, "body", "", "coverAlt", coverAlt);
    }
}
