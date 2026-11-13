package dev.joaoabreu.portfolioapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"
})
@WithMockUser(roles = "ADMIN")
class ContactInboxTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM contact_messages").update();
    }

    @Test
    void listsNewestFirstWithBoundedPaginationAndNoMessageBodyOrEmail() throws Exception {
        long first = seed("First");
        long second = seed("Second");
        mvc.perform(get("/api/admin/messages").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].id").value(second))
                .andExpect(jsonPath("$.messages[0].email").doesNotExist())
                .andExpect(jsonPath("$.messages[0].message").doesNotExist())
                .andExpect(jsonPath("$.messages[0].deliveryStatus").value("pending"))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/admin/messages").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.messages[0].id").value(first)).andExpect(jsonPath("$.hasNext").value(false));
        for (String size : new String[]{"0", "51"})
            mvc.perform(get("/api/admin/messages").param("size", size)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/messages").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void readingDetailsDoesNotImplicitlyMarkReadAndNeverCachesPrivateData() throws Exception {
        long id = seed("Sender");
        mvc.perform(get("/api/admin/messages/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sender@example.com"))
                .andExpect(jsonPath("$.message").value("Private message content"))
                .andExpect(jsonPath("$.readAt").isEmpty())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    void readArchiveAndRestoreDoNotChangeOutboxDeliveryState() throws Exception {
        long id = seed("Sender");
        change(id, "read", true, 0).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
        change(id, "archive", true, 1).andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isNotEmpty());
        mvc.perform(get("/api/admin/messages")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/messages").param("archived", "true"))
                .andExpect(jsonPath("$.totalElements").value(1));
        change(id, "archive", false, 2).andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isEmpty());
        change(id, "read", false, 3).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isEmpty());
        assertEquals("pending", jdbc.sql("SELECT status FROM contact_messages WHERE id = :id")
                .param("id", id).query(String.class).single());
        assertEquals(0, jdbc.sql("SELECT attempts FROM contact_messages WHERE id = :id")
                .param("id", id).query(Integer.class).single());
    }

    @Test
    void rejectsStaleVersionsMissingFieldsAndUnknownMessages() throws Exception {
        long id = seed("Sender");
        change(id, "read", true, 0).andExpect(status().isOk());
        change(id, "archive", true, 0).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/messages/" + id + "/read").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/messages/9223372036854775807")).andExpect(status().isNotFound());
    }

    @Test
    void requiresAdminAndCsrfForChanges() throws Exception {
        long id = seed("Sender");
        mvc.perform(get("/api/admin/messages").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/messages/" + id).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/messages").with(user("visitor").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/messages/" + id + "/read")
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":true,\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/messages/" + id + "/read").with(anonymous()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":true,\"version\":0}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions change(long id, String action, boolean value, long version)
            throws Exception {
        return mvc.perform(patch("/api/admin/messages/" + id + "/" + action).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":" + value + ",\"version\":" + version + "}"));
    }

    private long seed(String name) {
        return jdbc.sql("INSERT INTO contact_messages (name, email, message) VALUES (:name, :email, :message) RETURNING id")
                .param("name", name).param("email", "sender@example.com").param("message", "Private message content")
                .query(Long.class).single();
    }
}
