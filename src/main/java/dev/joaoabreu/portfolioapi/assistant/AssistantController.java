package dev.joaoabreu.portfolioapi.assistant;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class AssistantController {
    public static final String PATH = "/api/assistant/messages";
    private final AssistantService service;

    public AssistantController(AssistantService service) { this.service = service; }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssistantResponse> message(@RequestBody byte[] body) {
        JsonNode request;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            request = AssistantJson.MAPPER.readTree(json);
            AssistantResources.fields(request, Set.of("question", "locale"), Set.of());
        } catch (RuntimeException | CharacterCodingException exception) {
            throw new AssistantException(AssistantException.Code.INVALID_REQUEST);
        }
        List<AssistantProblem.FieldError> errors = new ArrayList<>();
        String question = field(request, "question", errors);
        String locale = field(request, "locale", errors);
        if (question != null) {
            question = question.strip();
            if (question.isEmpty() || question.codePointCount(0, question.length()) > 2000)
                errors.add(new AssistantProblem.FieldError("question", "INVALID_LENGTH"));
        }
        if (locale != null && !Set.of("pt", "en").contains(locale))
            errors.add(new AssistantProblem.FieldError("locale", "UNSUPPORTED_LOCALE"));
        if (!errors.isEmpty()) throw new AssistantException(AssistantException.Code.INVALID_REQUEST, errors);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.answer(question, locale));
    }

    private static String field(JsonNode request, String name, List<AssistantProblem.FieldError> errors) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            errors.add(new AssistantProblem.FieldError(name, "REQUIRED"));
            return null;
        }
        if (!value.isString()) {
            errors.add(new AssistantProblem.FieldError(name, "INVALID_TYPE"));
            return null;
        }
        return value.stringValue();
    }
}
