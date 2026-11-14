package dev.joaoabreu.portfolioapi;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Import(TestcontainersConfiguration.class)
@DirtiesContext
@SpringBootTest(properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "portfolio.contact.mail.enabled=true", "portfolio.contact.mail.poll-delay=100",
        "portfolio.contact.mail.from=portfolio@example.com",
        "portfolio.contact.mail.recipients=owner@example.com,dev@example.com",
        "portfolio.contact.retention.enabled=false"
})
class ContactSchedulerTests {
    @Autowired JdbcClient jdbc;
    @MockitoBean JavaMailSender sender;

    @Test
    void schedulerClaimsPendingMessageAndRecordsDeliveryWithoutManualInvocation() throws Exception {
        var delivered = new CountDownLatch(1);
        doAnswer(invocation -> {
            SimpleMailMessage mail = invocation.getArgument(0);
            assertEquals("portfolio@example.com", mail.getFrom());
            assertArrayEquals(new String[]{"owner@example.com", "dev@example.com"}, mail.getTo());
            assertEquals("sender@example.com", mail.getReplyTo());
            assertEquals("sending", jdbc.sql("SELECT status FROM contact_messages").query(String.class).single());
            delivered.countDown();
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));

        jdbc.sql("""
                INSERT INTO contact_messages (name, email, message)
                VALUES ('Scheduler test', 'sender@example.com', 'Synthetic scheduled message')
                """).update();

        assertTrue(delivered.await(10, TimeUnit.SECONDS), "Scheduler did not deliver the queued message");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!jdbc.sql("SELECT status = 'sent' AND attempts = 1 AND sent_at IS NOT NULL FROM contact_messages")
                    .query(Boolean.class).single()) {
                Thread.sleep(25);
            }
        });
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }
}
