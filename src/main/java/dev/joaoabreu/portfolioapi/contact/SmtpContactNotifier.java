package dev.joaoabreu.portfolioapi.contact;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpContactNotifier {
    private final JavaMailSender sender;
    private final ContactMailProperties properties;

    public SmtpContactNotifier(JavaMailSender sender, ContactMailProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    public void send(ContactMessage.Detail contact) {
        validateReplyTo(contact.email());
        var notification = new SimpleMailMessage();
        notification.setFrom(properties.from());
        notification.setTo(properties.recipients().toArray(String[]::new));
        notification.setReplyTo(contact.email());
        notification.setSubject("Portfolio contact #" + contact.id());
        notification.setText("Name: " + contact.name() + "\nEmail: " + contact.email()
                + "\nReceived: " + contact.createdAt() + "\n\n" + contact.message());
        sender.send(notification);
    }

    private void validateReplyTo(String email) {
        if (email == null || email.isBlank() || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid contact email");
        try {
            new InternetAddress(email, true).validate();
        } catch (AddressException exception) {
            throw new IllegalArgumentException("Invalid contact email");
        }
    }
}
