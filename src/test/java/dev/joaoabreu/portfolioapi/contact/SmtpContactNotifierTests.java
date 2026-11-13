package dev.joaoabreu.portfolioapi.contact;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpContactNotifierTests {
    JavaMailSender sender = mock(JavaMailSender.class);
    SmtpContactNotifier notifier = new SmtpContactNotifier(sender,
            new ContactMailProperties("dev@example.com", List.of("personal@example.com", "dev@example.com")));

    @Test
    void sendsPlainTextToBothRecipientsUsingConfiguredSenderAndVisitorReplyTo() {
        notifier.send(contact("visitor@example.com"));
        var capture = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());
        var message = capture.getValue();
        assertEquals("dev@example.com", message.getFrom());
        assertArrayEquals(new String[]{"personal@example.com", "dev@example.com"}, message.getTo());
        assertEquals("visitor@example.com", message.getReplyTo());
        assertEquals("Portfolio contact #7", message.getSubject());
        assertTrue(message.getText().contains("<b>Visitor content</b>"));
        assertTrue(message.getText().contains("Name: Test sender"));
    }

    @Test
    void rejectsInvalidOrInjectedReplyToWithoutSending() {
        for (String email : List.of("", "invalid", "visitor@example.com\r\nBcc: other@example.com"))
            assertThrows(IllegalArgumentException.class, () -> notifier.send(contact(email)));
        verifyNoInteractions(sender);
    }

    @Test
    void propagatesTransportFailureWithoutMarkingAnythingAsDelivered() {
        doThrow(new MailSendException("Simulated SMTP outage")).when(sender).send(any(SimpleMailMessage.class));
        assertThrows(MailSendException.class, () -> notifier.send(contact("visitor@example.com")));
    }

    private ContactMessage.Detail contact(String email) {
        return new ContactMessage.Detail(7, 0, "Test sender", email, "<b>Visitor content</b>",
                Instant.parse("2026-11-13T03:00:00Z"), null, null, "pending");
    }
}
