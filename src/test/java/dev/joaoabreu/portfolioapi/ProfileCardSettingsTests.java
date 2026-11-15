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

    static ObjectNode line(ObjectNode settings, String id, String text) {
        for (JsonNode block : settings.path("blocks"))
            if (block.path("id").asString().equals(id)) ((ObjectNode) block.path("lines").path(0)).put("text", text);
        return settings;
    }

    static String firstLine(JsonNode settings, String id) {
        for (JsonNode block : settings.path("blocks"))
            if (block.path("id").asString().equals(id)) return block.path("lines").path(0).path("text").asString();
        return null;
    }

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
        line(settings, "focus", "Synthetic focus");
        settings.put("accent", "GREEN");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, settings)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.pending").value(true));
        assertEquals(before, publicSvg());

        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.published.blocks[5].lines[0].text").value("Synthetic focus"));
        String after = publicSvg();
        assertNotEquals(before, after);
        assertTrue(after.contains("Synthetic focus") && after.contains("#22c55e"));
    }

    @Test
    void staleVersionsAreRejectedWithoutOverwriting() throws Exception {
        ObjectNode first = defaults();
        line(first, "focus", "Saved in tab A");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, first)))
                .andExpect(status().isOk());
        ObjectNode second = defaults();
        line(second, "focus", "Stale tab B");
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, second)))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
        assertEquals("Saved in tab A", firstLine(view().path("draft"), "focus"));
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
        ObjectNode tooWide = defaults();
        ((ObjectNode) tooWide.path("blocks").path(6)).put("title", "W".repeat(40));
        mvc.perform(put(BASE + "/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft(0, tooWide)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("blocks[6].title"));
        assertEquals(0, view().path("version").asLong());
    }

    @Test
    void storedSettingsFromTheFixedFieldEditorAreReadAsBlocks() throws Exception {
        jdbc.update("UPDATE profile_card_settings SET draft = ?, published = ?",
                "{\"theme\":\"LIGHT\",\"accent\":\"GREEN\",\"name\":\"João Abreu\",\"role\":\"Back-end Developer\","
                        + "\"monogram\":\"JA\",\"headline\":\"Synthetic headline\",\"blocks\":[\"FOCUS\",\"STACK\"],"
                        + "\"stack\":[\"Java\"],\"educationTitle\":\"E\",\"educationDetail\":\"D\",\"projectTitle\":\"P\","
                        + "\"projectStack\":\"S\",\"projectNote\":\"N\",\"focus\":\"Legacy focus\",\"opportunity\":\"O\"}",
                null);
        JsonNode draft = view().path("draft");
        assertEquals("LIGHT", draft.path("theme").asString());
        assertEquals("Legacy focus", firstLine(draft, "focus"));
        assertEquals("stack", draft.path("blocks").path(1).path("id").asString());
    }

    @Test
    void previewRendersUnsavedSettingsWithoutStoringThem() throws Exception {
        ObjectNode settings = defaults();
        settings.put("theme", "LIGHT");
        line(settings, "opportunity", "Preview only");
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
        mvc.perform(get(BASE + "/preview.svg").param("settings", "A".repeat(17000))).andExpect(status().isBadRequest());
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
