package dev.joaoabreu.portfolioapi.assistant;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("portfolio.assistant.rate-limit")
public record AssistantRateLimitProperties(@DefaultValue("10") int requestsPerWindow,
        @DefaultValue("100") int globalRequestsPerWindow, @DefaultValue("60s") Duration window,
        @DefaultValue("4096") int maxClients) {
    public AssistantRateLimitProperties {
        if (requestsPerWindow < 1 || globalRequestsPerWindow < 1 || maxClients < 1 || window == null
                || window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Invalid assistant rate-limit configuration");
    }
}
