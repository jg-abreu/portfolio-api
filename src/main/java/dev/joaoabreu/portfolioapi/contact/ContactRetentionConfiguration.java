package dev.joaoabreu.portfolioapi.contact;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "portfolio.contact.retention.enabled", havingValue = "true")
public class ContactRetentionConfiguration {
    @Bean
    ContactRetentionJob contactRetentionJob(JdbcClient jdbc) { return new ContactRetentionJob(jdbc); }
}
