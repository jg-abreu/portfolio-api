package dev.joaoabreu.portfolioapi.assistant;

import dev.joaoabreu.portfolioapi.network.ClientAddressKey;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AssistantWebConfiguration {
    @Bean
    AssistantRequestFilter assistantRequestFilter(AssistantRateLimiter limiter, ObjectProvider<ClientAddressKey> addresses) {
        // Servlet registration keeps this filter outside the security chain; its scope is the exact public POST.
        return new AssistantRequestFilter(limiter, addresses.getIfAvailable(ClientAddressKey::direct));
    }
}
