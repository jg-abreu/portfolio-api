package dev.joaoabreu.portfolioapi;

import java.util.List;
import java.util.Map;
import dev.joaoabreu.portfolioapi.recommendations.GoogleBooksService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github" })
@WithMockUser(roles = "ADMIN")
class GoogleBooksPublicationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean GoogleBooksService books;
    MockMvc mvc;
    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM recommendation_publications").update();
        jdbc.sql("DELETE FROM recommendation_drafts").update();
        when(books.verify("edition")).thenReturn(new GoogleBooksService.Book("edition", "Synthetic", List.of(), "", "", true,
                "https://books.google.com/books?id=edition", "https://books.google.com/books/content?id=edition"));
    }
    @Test
    void selectedBookStoresOnlyItsReferenceAndPersonalCopyWithoutAnImageFile() throws Exception {
        String draft = mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload("BOOKS", "", "edition")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(draft).get("id").asLong();
        mvc.perform(put("/api/admin/recommendations/" + id + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        verify(books).verify("edition");
        mvc.perform(get("/api/recommendations").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[0].data.googleBooksId").value("edition"))
                .andExpect(jsonPath("$.recommendations[0].data.imageKey").value(""))
                .andExpect(jsonPath("$.recommendations[0].data.en.title").value("My personal title"));
    }
    @Test
    void bookRoutesRequireAdminAndSelectionRequiresCsrf() throws Exception {
        mvc.perform(get("/api/admin/books?q=Java").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/books?q=Java").with(user("other").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/books/edition/selection")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/books/edition/selection").with(csrf())).andExpect(status().isOk());
    }
    @Test
    void bookReferencesCannotBeCombinedWithUploadsOrOtherCategories() throws Exception {
        for (String body : List.of(payload("TOOLS", "", "edition"), payload("BOOKS", "a".repeat(64) + ".png", "edition"), payload("BOOKS", "", "../private")))
            mvc.perform(post("/api/admin/recommendations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(books);
    }
    String payload(String category, String image, String book) {
        var copy = Map.of("title", "My personal title", "description", "My own recommendation", "imageAlt", "Cover");
        return json.writeValueAsString(Map.of("version", 0, "data", Map.of("slug", "synthetic-reference", "category", category,
                "imageKey", image, "googleBooksId", book, "url", "", "displayOrder", 0, "en", copy, "pt", copy)));
    }
}
