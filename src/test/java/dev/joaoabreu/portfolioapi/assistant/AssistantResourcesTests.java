package dev.joaoabreu.portfolioapi.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.*;

class AssistantResourcesTests {
    static final Resource KNOWLEDGE = new ClassPathResource("assistant/knowledge-base.json");
    static final Resource PROMPT = new ClassPathResource("assistant/system-prompt.md");

    static AssistantResources resources() { return new AssistantResources(KNOWLEDGE, PROMPT); }

    static Resource bytes(String text) { return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)); }

    static String knowledge() throws IOException { return KNOWLEDGE.getContentAsString(StandardCharsets.UTF_8); }

    @Test
    void loadsPackagedUtf8ResourcesWithStableIdsVersionAndExactFallbacks() {
        var snapshot = resources().requireAvailable();
        assertThat(snapshot.version()).isEqualTo("2026-11-12.1");
        assertThat(snapshot.sources()).hasSize(13).containsKeys("project-inhub", "education", "availability");
        assertThat(snapshot.fallbackAnswers().get("pt")).isEqualTo(
                "Não tenho essa informação específica na minha base, mas você pode perguntar diretamente ao João pelo LinkedIn disponível no portfólio.");
        assertThat(snapshot.fallbackAnswers().get("en")).isEqualTo(
                "I don’t have that specific information in my knowledge base, but you can ask João directly through the LinkedIn link available on his portfolio.");
        assertThat(snapshot.systemInstruction()).contains("2026-11-12", "project-inhub", "pursuing a Bachelor's degree");
        assertThatThrownBy(() -> snapshot.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingResourcesAndBlankPromptDisableOnlyAssistant() {
        for (var resources : new AssistantResources[] {
                new AssistantResources(new ClassPathResource("missing.json"), PROMPT),
                new AssistantResources(KNOWLEDGE, new ClassPathResource("missing.md")),
                new AssistantResources(KNOWLEDGE, bytes("  ")),
                new AssistantResources(new ByteArrayResource(new byte[] {(byte) 0xc3, 0x28}), PROMPT) }) {
            assertUnavailable(resources);
        }
    }

    @Test
    void rejectsMalformedSchemaDuplicateFieldsIdsUnsafeUrlsAndMissingFacts() throws IOException {
        String json = knowledge();
        for (String invalid : new String[] {
                "not JSON", json + " {}", json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 4294967297"),
                json.replace("\"language\": \"en\"", "\"language\": \"pt\""),
                json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
                json.replace("\"id\": \"availability\"", "\"id\": \"profile\""),
                json.replace("\"profile\"", "\"Invalid ID\""),
                json.replace("https://joaoabreu.dev", "javascript:alert(1)"),
                json.replace("https://joaoabreu.dev", "http://joaoabreu.dev"),
                json.replace("https://joaoabreu.dev", "https://user:password@joaoabreu.dev"),
                json.replace("\"informationProvidedOn\": \"2026-11-12\"", "\"informationProvidedOn\": \"invalid\""),
                json.replace("\"facts\":", "\"unexpected\":"),
                json.replace("\"en\": \"I don’t", "\"en\": \"Changed: I don’t") }) {
            assertUnavailable(new AssistantResources(bytes(invalid), PROMPT));
        }
    }

    @Test
    void rejectsMissingFallbackAndUnknownRootFields() throws IOException {
        var root = AssistantJson.MAPPER.readTree(knowledge()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root.get("fallbackAnswers")).remove("en");
        assertUnavailable(new AssistantResources(bytes(root.toString()), PROMPT));
        root = AssistantJson.MAPPER.readTree(knowledge()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root).put("unapprovedField", true);
        assertUnavailable(new AssistantResources(bytes(root.toString()), PROMPT));
    }

    private static void assertUnavailable(AssistantResources resources) {
        assertThatThrownBy(resources::requireAvailable).isInstanceOfSatisfying(AssistantException.class,
                error -> assertThat(error.code()).isEqualTo(AssistantException.Code.ASSISTANT_UNAVAILABLE));
    }
}
