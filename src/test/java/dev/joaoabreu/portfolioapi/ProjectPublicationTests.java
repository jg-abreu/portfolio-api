package dev.joaoabreu.portfolioapi;

import java.util.Map;
import dev.joaoabreu.portfolioapi.projects.ProjectDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "portfolio.github.client-id=test-client",
        "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"
})
@WithMockUser(roles = "ADMIN")
class ProjectPublicationTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired ProjectDraftRepository drafts;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        drafts.deleteAllInBatch();
    }

    @Test
    void anonymousReadsOnlySelectedCopiesAndEditingDoesNotChangeThem() throws Exception {
        long before = jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single();
        long id = create("sample", true);
        mvc.perform(get("/api/projects").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(0));
        String path = "/api/admin/projects/" + id;
        select(id, 0).andExpect(status().isOk());
        mvc.perform(put(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("changed", 0, true))).andExpect(status().isOk());
        mvc.perform(get("/api/projects").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.projects[0].slug").value("sample"))
                .andExpect(jsonPath("$.projects[0].en.title").value("English title"))
                .andExpect(jsonPath("$.projects[0].pt.title").value("Portuguese title"))
                .andExpect(jsonPath("$.projects[0].status").doesNotExist());
        assertEquals(before + 1, jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single());
        select(id, 0).andExpect(status().isConflict());
        assertEquals(before + 1, jdbc.sql("SELECT requested_generation FROM publication_requests").query(Long.class).single());
        select(id, 1).andExpect(status().isOk());
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$.projects[0].slug").value("changed"));
    }

    @Test
    void incompleteDraftCannotBeSelected() throws Exception {
        long id = create("incomplete", false);
        select(id, 0).andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$.projects.length()").value(0));
    }

    @Test
    void archivedDraftCannotBeSelectedAndArchivingDoesNotWithdrawExistingCopy() throws Exception {
        long id = create("sample", true);
        select(id, 0).andExpect(status().isOk());
        mvc.perform(patch("/api/admin/projects/" + id + "/status").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\",\"version\":0}"))
                .andExpect(status().isOk());
        select(id, 1).andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$.projects.length()").value(1));
    }

    @Test
    void withdrawalIsVersionCheckedAndKeepsPrivateDraft() throws Exception {
        long id = create("sample", true);
        select(id, 0).andExpect(status().isOk());
        String path = "/api/admin/projects/" + id + "/publication";
        mvc.perform(delete(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":4}")).andExpect(status().isConflict());
        mvc.perform(delete(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/projects").with(anonymous())).andExpect(jsonPath("$.projects.length()").value(0));
        assertEquals(1, drafts.count());
    }

    @Test
    void privatePublicationMetadataAndMutationsRequireAdminAndCsrf() throws Exception {
        long id = create("sample", true);
        String path = "/api/admin/projects/" + id + "/publication";
        mvc.perform(get("/api/admin/project-publications").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(anonymous()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(user("other").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions select(long id, long version) throws Exception {
        return mvc.perform(put("/api/admin/projects/" + id + "/publication").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", version))));
    }

    private long create(String slug, boolean complete) throws Exception {
        String response = mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload(slug, 0, complete))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asLong();
    }

    private String payload(String slug, long version, boolean complete) {
        return json.writeValueAsString(Map.of("version", version, "slug", slug,
                "repositoryUrl", "https://github.com/jg-abreu/portfolio-api", "liveUrl", "",
                "stack", "Java, Spring Boot", "displayOrder", 0,
                "en", copy("English title", complete), "pt", copy("Portuguese title", complete)));
    }

    private Map<String, String> copy(String title, boolean complete) {
        String text = complete ? "Project details" : "";
        return Map.of("title", title, "tagline", text, "problem", text, "decisions", text,
                "result", text, "body", "");
    }
}
