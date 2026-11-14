package dev.joaoabreu.portfolioapi.publication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "portfolio.publication.enabled", havingValue = "true")
@EnableScheduling
@Import({GitHubWorkflow.class, PublicationWorker.class})
public class PublicationConfiguration {}
