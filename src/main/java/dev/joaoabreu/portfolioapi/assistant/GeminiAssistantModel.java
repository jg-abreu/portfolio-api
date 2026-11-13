package dev.joaoabreu.portfolioapi.assistant;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GeminiAssistantModel implements AssistantModel {
    private final GeminiProperties properties;
    private final GenerateCall call;

    @Autowired
    public GeminiAssistantModel(ObjectProvider<Client> clients, GeminiProperties properties) {
        this(properties, (model, request, config) -> {
            Client client = clients.getIfAvailable();
            if (client == null) throw new AssistantException(AssistantException.Code.ASSISTANT_UNAVAILABLE);
            return client.models.generateContent(model, request, config);
        });
    }

    GeminiAssistantModel(GeminiProperties properties, GenerateCall call) {
        this.properties = properties;
        this.call = call;
    }

    @Override
    public String generate(String systemInstruction, String requestJson) {
        if (properties.model() == null || properties.model().isBlank())
            throw new AssistantException(AssistantException.Code.ASSISTANT_UNAVAILABLE);
        try {
            var config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .responseMimeType("application/json")
                    .responseJsonSchema(Map.of("type", "object", "additionalProperties", false,
                            "required", List.of("status", "answer", "sourceIds"),
                            "properties", Map.of(
                                    "status", Map.of("type", "string", "enum", List.of("answered", "insufficient_knowledge")),
                                    "answer", Map.of("type", "string"),
                                    "sourceIds", Map.of("type", "array", "items", Map.of("type", "string")))))
                    .candidateCount(1).maxOutputTokens(2048)
                    .httpOptions(HttpOptions.builder().timeout(30000)
                            .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
                    .build();
            GenerateContentResponse response = call.generate(properties.model(), requestJson, config);
            if (response == null || response.candidates().isEmpty() || response.candidates().get().size() != 1)
                throw invalidResponse();
            var candidate = response.candidates().get().getFirst();
            if (candidate.finishReason().isEmpty() || !"STOP".equals(candidate.finishReason().get().toString()))
                throw invalidResponse();
            String text = response.text();
            if (text == null || text.isBlank()) throw invalidResponse();
            return text;
        } catch (AssistantException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) throw new AssistantException(AssistantException.Code.ASSISTANT_TIMEOUT);
            throw new AssistantException(AssistantException.Code.ASSISTANT_UNAVAILABLE);
        }
    }

    private static AssistantException invalidResponse() {
        return new AssistantException(AssistantException.Code.INVALID_ASSISTANT_RESPONSE);
    }

    private static boolean isTimeout(Throwable exception) {
        for (int depth = 0; exception != null && depth < 16; depth++, exception = exception.getCause()) {
            if (exception instanceof SocketTimeoutException || exception instanceof HttpTimeoutException
                    || exception instanceof TimeoutException
                    || (exception instanceof ApiException api && (api.code() == 408 || api.code() == 504))) return true;
        }
        return false;
    }

    @FunctionalInterface
    interface GenerateCall {
        GenerateContentResponse generate(String model, String request, GenerateContentConfig config);
    }
}
