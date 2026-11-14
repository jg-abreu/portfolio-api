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
@SpringBootTest(properties = {"portfolio.profile-card.enabled=true", "portfolio.profile-card.github-token=",
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"})
@WithMockUser(roles = "ADMIN")
class ProfileCardSettingsTests {
    static final String BASE = "/api/admin/profile-card";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("UPDATE profile_card_settings SET draft = NULL, published = NULL, published_at = NULL, version = 0");
        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        jdbc.update("UPDATE profile_card_settings SET draft = NULL, published = NULL, published_at = NULL, version = 0");
    }

    JsonNode view() throws Exception {
        return json.readTree(mvc.perform(get(BASE)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    String draft(long version, ObjectNode settings) {
        ObjectNode body = json.createObjectNode();
        body.put("version", version);
        body.set("settings", settings);
        return body.toString();
    }

    ObjectNode defaults() throws Exception { return (ObjectNode) view().path("defaults").deepCopy(); }

    String publicSvg() throws Exception {
        return mvc.perform(get("/api/profile-card.svg")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void startsFromTheApprovedDefaultsWithNothingPublished() throws Exception {
        JsonNode view = view();
        assertEquals(0, view.path("version").asLong());
        assertTrue(view.path("published").isNull());
        assertFalse(view.path("pending").asBoolean());
        assertEquals(view.path("defaults"), view.path("draft"));
        assertEquals("DARK", view.path("draft").path("theme").asString());
    }

    @Test
    void draftIsPrivateUntilPublishedThenThePublicCardChanges() throws Exception {
        String before = publicSvg();
        ObjectNode settings = defaults();
        settings.put("focus", "Synthetic focus");
        settings.put("accent", "GREEN");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, settings)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.pending").value(true));
        assertEquals(before, publicSvg());

        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.published.focus").value("Synthetic focus"));
        String after = publicSvg();
        assertNotEquals(before, after);
        assertTrue(after.contains("Synthetic focus") && after.contains("#22c55e"));
    }

    @Test
    void staleVersionsAreRejectedWithoutOverwriting() throws Exception {
        ObjectNode first = defaults();
        first.put("focus", "Saved in tab A");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, first)))
                .andExpect(status().isOk());
        ObjectNode second = defaults();
        second.put("focus", "Stale tab B");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, second)))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
        assertEquals("Saved in tab A", view().path("draft").path("focus").asString());
    }

    @Test
    void invalidSettingsNameTheFieldAndChangeNothing() throws Exception {
        ObjectNode settings = defaults();
        settings.put("headline", "line\nbreak");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, settings)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("headline"));
        ObjectNode unknownBlock = defaults();
        unknownBlock.putArray("blocks").add("SCRIPT");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, unknownBlock)))
                .andExpect(status().isBadRequest());
        assertEquals(0, view().path("version").asLong());
    }

    @Test
    void previewRendersUnsavedSettingsWithoutStoringThem() throws Exception {
        ObjectNode settings = defaults();
        settings.put("theme", "LIGHT");
        settings.put("opportunity", "Preview only");
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(settings.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String svg = mvc.perform(get(BASE + "/preview.svg").param("settings", encoded))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(svg.contains("Preview only") && svg.contains("#ffffff"));
        assertEquals(0, view().path("version").asLong());
        assertFalse(publicSvg().contains("Preview only"));
        mvc.perform(get(BASE + "/preview.svg").param("settings", "not-json")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/preview.svg").param("settings", "A".repeat(9000))).andExpect(status().isBadRequest());
    }

    @Test
    void writesRequireCsrf() throws Exception {
        mvc.perform(post(BASE + "/publish").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void anonymousVisitorsCannotReadOrChangeSettings() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/preview.svg").param("settings", "e30")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());
    }
}
