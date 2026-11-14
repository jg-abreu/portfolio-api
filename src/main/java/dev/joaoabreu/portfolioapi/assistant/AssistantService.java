package dev.joaoabreu.portfolioapi.assistant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class AssistantService {
    private static final Set<String> MODEL_FIELDS = Set.of("status", "answer", "sourceIds");
    private static final Pattern MARKUP = Pattern.compile(
            "</?[A-Za-z][^>]*>|`|\\*\\*|__|!?\\[[^]]*]\\([^)]*\\)|(?m)^\\s*(?:#{1,6}\\s|[-*+]\\s|>\\s|\\d+[.)]\\s)");
    private final AssistantResources resources;
    private final AssistantModel model;

    public AssistantService(AssistantResources resources, AssistantModel model) {
        this.resources = resources;
        this.model = model;
    }

    public AssistantResponse answer(String question, String locale) {
        if (question == null || locale == null || !Set.of("pt", "en").contains(locale))
            throw new AssistantException(AssistantException.Code.INVALID_REQUEST);
        String trimmed = question.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 2000)
            throw new AssistantException(AssistantException.Code.INVALID_REQUEST);
        var knowledge = resources.requireAvailable();
        // No chat session or previous question is retained or sent to the model.
        String request = AssistantJson.MAPPER.writeValueAsString(Map.of("question", trimmed, "locale", locale));
        String raw;
        try {
            raw = model.generate(knowledge.systemInstruction(), request);
        } catch (AssistantException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AssistantException(AssistantException.Code.INTERNAL_ERROR);
        }
        try {
            if (raw == null || raw.length() > 32000) throw invalidResponse();
            JsonNode node = AssistantJson.MAPPER.readTree(raw);
            AssistantResources.fields(node, MODEL_FIELDS, MODEL_FIELDS);
            String status = AssistantResources.text(node.get("status"));
            String answer = AssistantResources.text(node.get("answer"));
            if (!Set.of("answered", "insufficient_knowledge").contains(status) || !node.get("sourceIds").isArray())
                throw invalidResponse();
            var sources = new ArrayList<AssistantResponse.Source>();
            Set<String> seen = new HashSet<>();
            for (JsonNode idNode : node.get("sourceIds")) {
                String id = AssistantResources.text(idNode);
                var source = knowledge.sources().get(id);
                if (source == null || !seen.add(id)) throw invalidResponse();
                sources.add(source);
            }
            if (status.equals("insufficient_knowledge")) {
                if (!sources.isEmpty()) throw invalidResponse();
                // The server enforces the exact approved wording, regardless of the model's wording.
                return new AssistantResponse(status, knowledge.fallbackAnswers().get(locale), locale,
                        List.of(), knowledge.version());
            }
            if (sources.isEmpty() || MARKUP.matcher(answer).find()) throw invalidResponse();
            return new AssistantResponse(status, answer, locale, sources, knowledge.version());
        } catch (AssistantException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
    }

    private static AssistantException invalidResponse() {
        return new AssistantException(AssistantException.Code.INVALID_ASSISTANT_RESPONSE);
    }
}
