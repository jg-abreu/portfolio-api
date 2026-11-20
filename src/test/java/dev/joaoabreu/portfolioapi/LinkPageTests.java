package dev.joaoabreu.portfolioapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"})
@WithMockUser(roles = "ADMIN")
class LinkPageTests {
    static final String BASE = "/api/admin/link-page";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("UPDATE link_page_settings SET draft = NULL, published = NULL, published_at = NULL, version = 0");
    }

    JsonNode view() throws Exception {
        return json.readTree(mvc.perform(get(BASE)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode published() throws Exception {
        return json.readTree(mvc.perform(get("/api/link-page")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
    }

    String draft(long version, String title, String url, String icon) {
        ObjectNode body = json.createObjectNode();
        body.put("version", version);
        ObjectNode link = body.putObject("page").putArray("links").addObject();
        link.put("title", title);
        link.put("url", url);
        link.put("icon", icon);
        return body.toString();
    }

    @Test
    void startsFromTheDefaultsWithNothingPublished() throws Exception {
        JsonNode view = view();
        assertEquals(0, view.path("version").asLong());
        assertTrue(view.path("published").isNull());
        assertFalse(view.path("pending").asBoolean());
        assertEquals(view.path("defaults"), view.path("draft"));
        assertEquals(view.path("defaults"), published());
    }

    @Test
    void draftIsPrivateUntilPublished() throws Exception {
        JsonNode before = published();
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(draft(0, "Synthetic", "https://example.com/", "star")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.pending").value(true));
        assertEquals(before, published());

        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(false));
        JsonNode after = published();
        assertEquals(1, after.path("links").size());
        assertEquals("Synthetic", after.path("links").path(0).path("title").asString());
        assertEquals("star", after.path("links").path(0).path("icon").asString());
    }

    @Test
    void staleVersionsAndInvalidLinksChangeNothing() throws Exception {
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(draft(0, "Tab A", "https://example.com/", "star"))).andExpect(status().isOk());
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(draft(0, "Tab B", "https://example.com/", "star"))).andExpect(status().isConflict());
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(draft(1, "Bad", "javascript:alert(1)", "star")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("links[0].url"));
        JsonNode view = view();
        assertEquals(1, view.path("version").asLong());
        assertEquals("Tab A", view.path("draft").path("links").path(0).path("title").asString());
    }

    @Test
    void writesRequireCsrf() throws Exception {
        mvc.perform(post(BASE + "/publish").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void anonymousVisitorsReadOnlyThePublishedPage() throws Exception {
        published();
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(draft(0, "T", "https://example.com/", "star"))).andExpect(status().isUnauthorized());
    }
}
