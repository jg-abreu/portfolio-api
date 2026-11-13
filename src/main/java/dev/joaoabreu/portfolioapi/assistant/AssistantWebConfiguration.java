package dev.joaoabreu.portfolioapi.assistant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AssistantWebConfiguration {
    @Bean
    AssistantRequestFilter assistantRequestFilter(AssistantRateLimiter limiter) {
        // Servlet registration keeps this filter outside the security chain; its scope is the exact public POST.
        return new AssistantRequestFilter(limiter);
    }
}
