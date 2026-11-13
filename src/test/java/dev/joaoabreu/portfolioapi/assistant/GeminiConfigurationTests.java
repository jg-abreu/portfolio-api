package dev.joaoabreu.portfolioapi.assistant;

import com.google.genai.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GeminiConfiguration.class);

    @Test
    void missingKeyDoesNotPreventContextStartup() {
        runner.withInitializer(context -> {
            context.getEnvironment().getPropertySources().remove("systemEnvironment");
            context.getEnvironment().getPropertySources().remove("systemProperties");
        }).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Client.class);
            assertThat(context.getBean(GeminiProperties.class).model()).isEqualTo("gemini-3.1-flash-lite");
        });
    }

    @Test
    void blankKeyDoesNotCreateClient() {
        runner.withPropertyValues("GEMINI_API_KEY=   ").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(Client.class));
    }

    @Test
    void configuredKeyCreatesSingletonClientAndModelCanBeOverridden() {
        // A dummy key creates the client without making an API request.
        runner.withPropertyValues("GEMINI_API_KEY=test-only-not-a-real-key", "portfolio.gemini.model=test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Client.class);
                    assertThat(context.getBean(Client.class)).isSameAs(context.getBean("geminiClient"));
                    assertThat(context.getBean(GeminiProperties.class).model()).isEqualTo("test-model");
                });
    }

    @Test
    void completeAssistantConfigurationStartsWithoutKeyAndFailsOnlyWhenAsked() {
        runner.withUserConfiguration(AssistantResources.class, GeminiAssistantModel.class, AssistantService.class)
                .withPropertyValues("GEMINI_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(Client.class).hasSingleBean(AssistantService.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            context.getBean(AssistantService.class).answer("What is his focus?", "en"))
                            .isInstanceOfSatisfying(AssistantException.class, error ->
                                    assertThat(error.code()).isEqualTo(AssistantException.Code.ASSISTANT_UNAVAILABLE));
                });
    }

    @Test
    void invalidResourcesDoNotPreventContextStartup() {
        runner.withUserConfiguration(GeminiAssistantModel.class, AssistantService.class)
                .withBean(AssistantResources.class, () -> new AssistantResources(
                        AssistantResourcesTests.bytes("{}"), AssistantResourcesTests.PROMPT))
                .withPropertyValues("GEMINI_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AssistantService.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            context.getBean(AssistantService.class).answer("Question", "pt"))
                            .isInstanceOfSatisfying(AssistantException.class, error ->
                                    assertThat(error.code()).isEqualTo(AssistantException.Code.ASSISTANT_UNAVAILABLE));
                });
    }
}
