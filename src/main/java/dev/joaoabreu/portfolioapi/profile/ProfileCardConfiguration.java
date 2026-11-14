package dev.joaoabreu.portfolioapi.profile;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "portfolio.profile-card.enabled", havingValue = "true")
@EnableScheduling
@Import({GitHubProfileSource.class, ProfileCardCache.class, ProfileCardController.class})
public class ProfileCardConfiguration {}
