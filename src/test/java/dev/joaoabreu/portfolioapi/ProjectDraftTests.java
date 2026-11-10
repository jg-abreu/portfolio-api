package dev.joaoabreu.portfolioapi;

import java.util.Map;
import dev.joaoabreu.portfolioapi.projects.ProjectDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class ProjectDraftTests {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectDraftRepository repository;

    @BeforeEach
    void clearDrafts() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        repository.deleteAllInBatch();
    }

    @Test
    void createsBilingualDraftAndReadsItFromDatabase() throws Exception {
        JsonNode draft = create("new-project", 0);
        assertEquals("DRAFT", draft.path("status").asString());
        assertEquals(1, repository.count());
        mvc.perform(get("/api/admin/projects/" + draft.path("id").asLong())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].en.title").value("English title"))
                .andExpect(jsonPath("$[0].pt.title").value("Título em português"))
                .andExpect(jsonPath("$[0].repositoryUrl").value("https://github.com/jg-abreu/new-project"));
    }

    @Test
    void listsProjectsInDisplayOrder() throws Exception {
        create("last-project", 8);
        create("first-project", 1);
        mvc.perform(get("/api/admin/projects"))
                .andExpect(jsonPath("$[0].slug").value("first-project"))
                .andExpect(jsonPath("$[1].slug").value("last-project"));
    }

    @Test
    void updatesDraftAndRejectsStaleVersionWithoutOverwriting() throws Exception {
        JsonNode draft = create("new-project", 0);
        String path = "/api/admin/projects/" + draft.path("id").asLong();
        mvc.perform(put(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("renamed-project", 0, 3)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("outdated-project", 0, 0)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/admin/projects")).andExpect(jsonPath("$[0].slug").value("renamed-project"));
    }

    @Test
    void duplicateIdentifierReturnsConflict() throws Exception {
        JsonNode draft = create("same-project", 0);
        mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("same-project", 0, 0))).andExpect(status().isConflict());
        assertEquals(1, repository.count());
        mvc.perform(get("/api/admin/projects/" + draft.path("id").asLong())).andExpect(status().isOk());
    }

    @Test
    void archivesAndRestoresWithoutDeleting() throws Exception {
        JsonNode draft = create("new-project", 0);
        String path = "/api/admin/projects/" + draft.path("id").asLong() + "/status";
        mvc.perform(patch(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ARCHIVED\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(patch(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DRAFT\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        assertEquals(1, repository.count());
        mvc.perform(get("/api/admin/projects/" + draft.path("id").asLong())).andExpect(status().isOk());
    }

    @Test
    void validatesRepositoryAndBothLanguageTitles() throws Exception {
        String valid = payload("new-project", 0, 0);
        for (String invalid : new String[] {
                valid.replace("https://github.com/jg-abreu/new-project", "javascript:alert(1)"),
                valid.replace("English title", " "),
                valid.replace("Título em português", " ") }) {
            mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(invalid)).andExpect(status().isBadRequest());
        }
        assertEquals(0, repository.count());
    }

    @Test
    void cannotPublishThroughDraftStatus() throws Exception {
        JsonNode draft = create("new-project", 0);
        mvc.perform(patch("/api/admin/projects/" + draft.path("id").asLong() + "/status")
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PUBLISHED\",\"version\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mutationWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .content(payload("new-project", 0, 0))).andExpect(status().isForbidden());
        assertEquals(0, repository.count());
    }

    @Test
    @WithAnonymousUser
    void anonymousCannotReadDrafts() throws Exception {
        mvc.perform(get("/api/admin/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdminCannotReadOrCreateDrafts() throws Exception {
        mvc.perform(get("/api/admin/projects")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("new-project", 0, 0))).andExpect(status().isForbidden());
    }

    @Test
    void updatingUnknownDraftReturnsNotFound() throws Exception {
        mvc.perform(put("/api/admin/projects/999999").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload("new-project", 0, 0))).andExpect(status().isNotFound());
    }

    private JsonNode create(String slug, int order) throws Exception {
        String response = mvc.perform(post("/api/admin/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(payload(slug, 0, order))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private String payload(String slug, long version, int order) {
        return json.writeValueAsString(Map.of(
                "version", version, "slug", slug, "repositoryUrl", "https://github.com/jg-abreu/new-project",
                "liveUrl", "", "stack", "Java, Spring Boot", "displayOrder", order,
                "en", copy("English title"), "pt", copy("Título em português")));
    }

    private Map<String, String> copy(String title) {
        return Map.of("title", title, "tagline", "", "problem", "", "decisions", "", "result", "", "body", "");
    }
}