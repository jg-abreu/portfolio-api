package dev.joaoabreu.portfolioapi.assistant;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class AssistantResources {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "knowledgeVersion", "language",
            "provenance", "fallbackAnswers", "sources", "informationNotProvided", "missingInformationPolicy");
    private static final Set<String> SOURCE_FIELDS = Set.of("id", "title", "url", "facts",
            "limitations", "informationProvidedOn");
    private final Snapshot snapshot;

    @Autowired
    public AssistantResources(ResourceLoader loader) {
        this(loader.getResource("classpath:assistant/knowledge-base.json"),
                loader.getResource("classpath:assistant/system-prompt.md"));
    }

    AssistantResources(Resource knowledge, Resource prompt) {
        Snapshot loaded;
        try {
            loaded = load(read(knowledge), read(prompt));
        } catch (IOException | RuntimeException exception) {
            // Invalid resources disable the assistant, not the rest of the API. Never log their contents.
            loaded = null;
        }
        snapshot = loaded;
    }

    Snapshot requireAvailable() {
        if (snapshot == null) throw new AssistantException(AssistantException.Code.ASSISTANT_UNAVAILABLE);
        return snapshot;
    }

    private static String read(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(262145);
            require(bytes.length <= 262144);
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static Snapshot load(String json, String prompt) {
        require(!prompt.isBlank());
        JsonNode root = AssistantJson.MAPPER.readTree(json);
        fields(root, ROOT_FIELDS, ROOT_FIELDS);
        require(root.get("schemaVersion").isIntegralNumber() && root.get("schemaVersion").toString().equals("1"));
        require(text(root.get("language")).equals("en"));
        String version = text(root.get("knowledgeVersion"));
        require(version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"));
        text(root.get("provenance"));
        text(root.get("missingInformationPolicy"));
        texts(root.get("informationNotProvided"));
        JsonNode fallback = root.get("fallbackAnswers");
        fields(fallback, Set.of("pt", "en"), Set.of("pt", "en"));
        Map<String, String> answers = Map.of("pt", text(fallback.get("pt")), "en", text(fallback.get("en")));
        answers.values().forEach(answer -> require(prompt.contains(answer)));
        require(root.get("sources").isArray() && !root.get("sources").isEmpty());
        Map<String, AssistantResponse.Source> sources = new HashMap<>();
        for (JsonNode node : root.get("sources")) {
            fields(node, SOURCE_FIELDS, Set.of("id", "title", "url", "facts"));
            String id = text(node.get("id"));
            require(id.matches("[a-z0-9]+(-[a-z0-9]+)*"));
            String title = text(node.get("title"));
            String url = node.get("url").isNull() ? null : text(node.get("url"));
            require(url == null || safeUrl(url));
            texts(node.get("facts"));
            if (node.has("limitations")) texts(node.get("limitations"));
            if (node.has("informationProvidedOn")) LocalDate.parse(text(node.get("informationProvidedOn")));
            require(sources.put(id, new AssistantResponse.Source(id, title, url)) == null);
        }
        return new Snapshot(prompt, AssistantJson.MAPPER.writeValueAsString(root), version,
                Map.copyOf(sources), answers);
    }

    private static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && value.chars().noneMatch(c -> c <= 32 || c == 127);
        } catch (IllegalArgumentException exception) { return false; }
    }

    static String text(JsonNode node) {
        require(node != null && node.isString() && !node.stringValue().isBlank());
        return node.stringValue();
    }

    private static void texts(JsonNode node) {
        require(node != null && node.isArray() && !node.isEmpty());
        node.forEach(AssistantResources::text);
    }

    static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        require(node != null && node.isObject());
        require(allowed.containsAll(node.propertyNames()) && node.propertyNames().containsAll(required));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid assistant resource or model format");
    }

    record Snapshot(String systemPrompt, String knowledgeJson, String version,
            Map<String, AssistantResponse.Source> sources, Map<String, String> fallbackAnswers) {
        String systemInstruction() {
            return systemPrompt + "\n\n## Server-supplied authorized knowledge base\n" + knowledgeJson;
        }
    }
}
