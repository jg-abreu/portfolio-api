package dev.joaoabreu.portfolioapi.assistant;

import com.google.genai.Client;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfiguration {

    @Bean(destroyMethod = "close")
    @Conditional(ApiKeyPresent.class)
    Client geminiClient(Environment environment) {
        // Explicit selection prevents another Google API key from taking precedence.
        return Client.builder().apiKey(environment.getProperty("GEMINI_API_KEY")).build();
    }

    static class ApiKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("GEMINI_API_KEY"));
        }
    }
}
