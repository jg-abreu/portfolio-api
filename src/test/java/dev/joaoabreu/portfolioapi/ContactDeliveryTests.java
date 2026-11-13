package dev.joaoabreu.portfolioapi;

import java.util.UUID;
import dev.joaoabreu.portfolioapi.contact.ContactDeliveryWorker;
import dev.joaoabreu.portfolioapi.contact.ContactRetentionJob;
import dev.joaoabreu.portfolioapi.contact.ContactSubmissionFilter;
import dev.joaoabreu.portfolioapi.contact.SmtpContactNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "portfolio.contact.submission.enabled=true"
})
class ContactDeliveryTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    MockMvc mvc;
    SmtpContactNotifier notifier;
    ContactDeliveryWorker worker;
    static final String BODY = """
            {"name":"Test sender","email":"sender@example.com","message":"A synthetic contact message","website":""}
            """;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new ContactSubmissionFilter())
                .apply(springSecurity()).build();
        jdbc.sql("DELETE FROM contact_messages").update();
        notifier = mock(SmtpContactNotifier.class);
        worker = new ContactDeliveryWorker(jdbc, notifier);
    }

    @Test
    void persistsBeforeAcknowledgementAndDeduplicatesRetry() throws Exception {
        UUID key = UUID.randomUUID();
        mvc.perform(submit(key, BODY)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(submit(key, BODY)).andExpect(status().isAccepted());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM contact_messages").query(Long.class).single());
        assertEquals("pending", deliveryStatus());
        verifyNoInteractions(notifier);
        mvc.perform(submit(key, BODY.replace("synthetic", "different"))).andExpect(status().isConflict());
    }

    @Test
    void validatesInputAndKeepsCsrfEnabled() throws Exception {
        mvc.perform(post("/api/contact").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(submit(UUID.randomUUID(), BODY.replace("sender@example.com", "invalid")))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(UUID.randomUUID(), BODY.replace("\"website\":\"\"", "\"website\":\"spam\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(UUID.randomUUID(), "{" )).andExpect(status().isBadRequest());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM contact_messages").query(Long.class).single());
    }

    @Test
    void boundsPayloadMediaAndRateWithoutLoggingVisitorText() throws Exception {
        mvc.perform(submit(UUID.randomUUID(), " ".repeat(16385))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/contact").with(csrf()).contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isUnsupportedMediaType());
        for (int i = 0; i < 5; i++)
            mvc.perform(submit(UUID.randomUUID(), BODY)).andExpect(status().isAccepted());
        mvc.perform(submit(UUID.randomUUID(), BODY)).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void marksSentOnlyAfterSmtpSucceeds() throws Exception {
        mvc.perform(submit(UUID.randomUUID(), BODY)).andExpect(status().isAccepted());
        worker.deliver();
        assertEquals("sent", deliveryStatus());
        assertEquals(1, attempts());
        assertNotNull(jdbc.sql("SELECT sent_at FROM contact_messages").query(java.time.OffsetDateTime.class).single());
        verify(notifier).send(any());
    }

    @Test
    void retriesSmtpFailureThenStopsAfterFiveAttempts() throws Exception {
        mvc.perform(submit(UUID.randomUUID(), BODY)).andExpect(status().isAccepted());
        jdbc.sql("UPDATE contact_messages SET next_attempt_at=now()-interval '1 minute'").update();
        doThrow(new MailSendException("Simulated failure")).when(notifier).send(any());
        for (int attempt = 1; attempt <= 5; attempt++) {
            worker.deliver();
            assertEquals(attempt, attempts());
            assertEquals(attempt == 5 ? "failed" : "pending", deliveryStatus());
            jdbc.sql("UPDATE contact_messages SET next_attempt_at = now() - interval '1 second'").update();
        }
        worker.deliver();
        assertEquals(5, attempts());
        verify(notifier, times(5)).send(any());
    }

    @Test
    void recoversExpiredLeasesButLeavesActiveLeasesUntouched() throws Exception {
        mvc.perform(submit(UUID.randomUUID(), BODY)).andExpect(status().isAccepted());
        jdbc.sql("UPDATE contact_messages SET status='sending', attempts=1, next_attempt_at=now()+interval '5 minutes'").update();
        worker.deliver();
        verifyNoInteractions(notifier);
        jdbc.sql("UPDATE contact_messages SET next_attempt_at=now()-interval '1 second'").update();
        worker.deliver();
        assertEquals("sent", deliveryStatus());
        assertEquals(2, attempts());
    }

    @Test
    void retentionRemovesOldRowsButKeepsRecentMessagesAndActiveLeases() throws Exception {
        mvc.perform(submit(UUID.randomUUID(), BODY)).andExpect(status().isAccepted());
        new ContactRetentionJob(jdbc).removeExpired();
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM contact_messages").query(Long.class).single());
        jdbc.sql("UPDATE contact_messages SET created_at=now()-interval '91 days', status='sending', next_attempt_at=now()+interval '5 minutes'").update();
        new ContactRetentionJob(jdbc).removeExpired();
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM contact_messages").query(Long.class).single());
        jdbc.sql("UPDATE contact_messages SET next_attempt_at=now()-interval '1 second'").update();
        new ContactRetentionJob(jdbc).removeExpired();
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM contact_messages").query(Long.class).single());
    }

    private MockHttpServletRequestBuilder submit(UUID key, String body) {
        return post("/api/contact").with(csrf()).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String deliveryStatus() { return jdbc.sql("SELECT status FROM contact_messages").query(String.class).single(); }
    private int attempts() { return jdbc.sql("SELECT attempts FROM contact_messages").query(Integer.class).single(); }
}
