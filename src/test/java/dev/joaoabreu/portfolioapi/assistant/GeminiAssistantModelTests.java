package dev.joaoabreu.portfolioapi.assistant;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.GenerateContentResponse;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.*;

class GeminiAssistantModelTests {
    @Test
    void usesConfiguredModelSeparateSystemInstructionJsonModeAndBoundedRequest() {
        var model = new GeminiAssistantModel(new GeminiProperties("test-model"), (name, request, config) -> {
            assertThat(name).isEqualTo("test-model");
            assertThat(request).isEqualTo("{\"question\":\"Hello\",\"locale\":\"en\"}");
            assertThat(config.systemInstruction().orElseThrow().parts().orElseThrow().getFirst().text())
                    .contains("Authorized instructions");
            assertThat(config.responseMimeType()).contains("application/json");
            assertThat(config.responseJsonSchema()).isPresent();
            assertThat(config.httpOptions().orElseThrow().timeout()).contains(30000);
            assertThat(config.httpOptions().orElseThrow().retryOptions().orElseThrow().attempts()).contains(1);
            assertThat(config.tools()).isEmpty();
            return response("STOP", "{\"status\":\"answered\"}");
        });
        assertThat(model.generate("Authorized instructions", "{\"question\":\"Hello\",\"locale\":\"en\"}"))
                .isEqualTo("{\"status\":\"answered\"}");
    }

    @Test
    void missingClientOrBlankModelIsUnavailableWithoutNetwork() {
        var clients = new DefaultListableBeanFactory().getBeanProvider(Client.class);
        assertCode(new GeminiAssistantModel(clients, new GeminiProperties("test-model")),
                AssistantException.Code.ASSISTANT_UNAVAILABLE);
        assertCode(new GeminiAssistantModel(new GeminiProperties("  "), (name, request, config) -> {
            throw new AssertionError("must not call provider");
        }), AssistantException.Code.ASSISTANT_UNAVAILABLE);
    }

    @Test
    void providerQuotaAuthAndServerFailuresAreUnavailableAndDetailsAreNotRetained() {
        for (int status : new int[] {400, 401, 403, 429, 500, 503}) {
            var model = failing(new ApiException(status, "PROVIDER_FAILURE", "sensitive provider text"));
            assertThatThrownBy(() -> model.generate("system", "question"))
                    .isInstanceOfSatisfying(AssistantException.class, error -> {
                        assertThat(error.code()).isEqualTo(AssistantException.Code.ASSISTANT_UNAVAILABLE);
                        assertThat(error.getMessage()).isEqualTo("ASSISTANT_UNAVAILABLE");
                        assertThat(error.getCause()).isNull();
                    });
        }
    }

    @Test
    void mapsHttpAndTransportTimeoutsSeparately() {
        for (RuntimeException error : new RuntimeException[] {new ApiException(408, "TIMEOUT", "details"),
                new ApiException(504, "TIMEOUT", "details"), new RuntimeException(new SocketTimeoutException())})
            assertCode(failing(error), AssistantException.Code.ASSISTANT_TIMEOUT);
        assertCode(failing(new RuntimeException("network failure")), AssistantException.Code.ASSISTANT_UNAVAILABLE);
    }

    @Test
    void rejectsBlockedTruncatedEmptyOrMissingCandidates() {
        for (var response : new GenerateContentResponse[] {null, GenerateContentResponse.fromJson("{}"),
                GenerateContentResponse.fromJson("{\"candidates\":[]}"), response("SAFETY", "blocked"),
                response("MAX_TOKENS", "truncated"), response("STOP", "")}) {
            var model = new GeminiAssistantModel(new GeminiProperties("test"), (name, request, config) -> response);
            assertCode(model, AssistantException.Code.INVALID_ASSISTANT_RESPONSE);
        }
    }

    private static GeminiAssistantModel failing(RuntimeException error) {
        return new GeminiAssistantModel(new GeminiProperties("test"), (name, request, config) -> { throw error; });
    }

    private static GenerateContentResponse response(String finish, String text) {
        String json = AssistantJson.MAPPER.writeValueAsString(java.util.Map.of("candidates", java.util.List.of(
                java.util.Map.of("finishReason", finish, "content", java.util.Map.of("parts", java.util.List.of(
                        java.util.Map.of("text", text)))))));
        return GenerateContentResponse.fromJson(json);
    }

    private static void assertCode(GeminiAssistantModel model, AssistantException.Code code) {
        assertThatThrownBy(() -> model.generate("system", "question"))
                .isInstanceOfSatisfying(AssistantException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
