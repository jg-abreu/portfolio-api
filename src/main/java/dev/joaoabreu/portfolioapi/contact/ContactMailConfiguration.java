package dev.joaoabreu.portfolioapi.contact;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "portfolio.contact.mail.enabled", havingValue = "true")
@EnableConfigurationProperties(ContactMailProperties.class)
public class ContactMailConfiguration {
    @Bean
    SmtpContactNotifier contactNotifier(JavaMailSender sender, ContactMailProperties properties) {
        return new SmtpContactNotifier(sender, properties);
    }
}
