package dev.joaoabreu.portfolioapi.assistant;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AssistantServiceTests {
    private final AssistantResources resources = AssistantResourcesTests.resources();
    private final AssistantModel model = mock(AssistantModel.class);
    private final AssistantService service = new AssistantService(resources, model);

    @Test
    void assemblesV1ResponseFromServerRegistryAndNeverTrustsModelMetadata() {
        when(model.generate(anyString(), anyString())).thenReturn(
                """
                {"status":"answered","answer":"João focuses on Java and Spring Boot.","sourceIds":["profile","technical-knowledge"]}
                """);
        var response = service.answer("  What is his focus?  ", "en");
        assertThat(response.status()).isEqualTo("answered");
        assertThat(response.locale()).isEqualTo("en");
        assertThat(response.knowledgeVersion()).isEqualTo("2026-11-12.1");
        assertThat(response.sources()).containsExactly(resources.requireAvailable().sources().get("profile"),
                resources.requireAvailable().sources().get("technical-knowledge"));
        var json = AssistantJson.MAPPER.readTree(AssistantJson.MAPPER.writeValueAsString(response));
        assertThat(json.propertyNames()).isEqualTo(Set.of("status", "answer", "locale", "sources", "knowledgeVersion"));
        assertThat(json.get("sources").get(1).get("url").isNull()).isTrue();
        verify(model).generate(resources.requireAvailable().systemInstruction(),
                AssistantJson.MAPPER.writeValueAsString(java.util.Map.of("question", "What is his focus?", "locale", "en")));
    }

    @Test
    void serverEnforcesExactInsufficientKnowledgeAnswersInBothLocales() {
        when(model.generate(anyString(), anyString())).thenReturn(
                "{\"status\":\"insufficient_knowledge\",\"answer\":\"A model paraphrase that must not escape\",\"sourceIds\":[]}");
        for (String locale : List.of("pt", "en")) {
            var response = service.answer("Salary?", locale);
            assertThat(response.status()).isEqualTo("insufficient_knowledge");
            assertThat(response.answer()).isEqualTo(resources.requireAvailable().fallbackAnswers().get(locale));
            assertThat(response.sources()).isEmpty();
            assertThat(response.locale()).isEqualTo(locale);
        }
    }

    @Test
    void rejectsInvalidModelFormatsAndUnknownDuplicateOrInconsistentSources() {
        String valid = "{\"status\":\"answered\",\"answer\":\"Answer\",\"sourceIds\":[\"profile\"]}";
        for (String raw : new String[] {
                null, "", "not JSON", "[]", valid + " {}", "```json\n" + valid + "\n```",
                valid.replace("answered", "partial"), valid.replace("\"Answer\"", "42"),
                valid.replace("\"Answer\"", "\" \""), valid.replace("[\"profile\"]", "null"),
                valid.replace("[\"profile\"]", "[42]"), valid.replace("profile", "invented"),
                valid.replace("[\"profile\"]", "[\"profile\",\"profile\"]"),
                valid.replace("[\"profile\"]", "[]"), valid.replace("answered", "insufficient_knowledge"),
                valid.replace("\"answer\":", "\"answer\":\"forged\",\"answer\":"),
                valid.replace("\"status\":", "\"knowledgeVersion\":\"forged\",\"status\":"),
                valid.replace("Answer", "<b>Answer</b>"), valid.replace("Answer", "**Answer**"),
                valid.replace("Answer", "[click](https://evil.example)"),
                "{\"status\":\"answered\",\"sourceIds\":[\"profile\"]}" }) {
            when(model.generate(anyString(), anyString())).thenReturn(raw);
            assertCode(() -> service.answer("Question", "pt"), AssistantException.Code.INVALID_ASSISTANT_RESPONSE);
        }
    }

    @Test
    void invalidInputsNeverReachModel() {
        for (String question : new String[] {null, "", "  ", "a".repeat(2001)})
            assertCode(() -> service.answer(question, "pt"), AssistantException.Code.INVALID_REQUEST);
        for (String locale : new String[] {null, "PT", "fr", ""})
            assertCode(() -> service.answer("Question", locale), AssistantException.Code.INVALID_REQUEST);
        verifyNoInteractions(model);
    }

    @Test
    void unavailableResourcesNeverReachModel() {
        var invalid = new AssistantResources(AssistantResourcesTests.bytes("{}"), AssistantResourcesTests.PROMPT);
        assertCode(() -> new AssistantService(invalid, model).answer("Question", "pt"),
                AssistantException.Code.ASSISTANT_UNAVAILABLE);
        verifyNoInteractions(model);
    }

    @Test
    void technicalFailuresRemainDistinctFromInsufficientKnowledgeAndHideProviderDetails() {
        for (var code : List.of(AssistantException.Code.ASSISTANT_UNAVAILABLE, AssistantException.Code.ASSISTANT_TIMEOUT,
                AssistantException.Code.INVALID_ASSISTANT_RESPONSE)) {
            doThrow(new AssistantException(code)).when(model).generate(anyString(), anyString());
            assertCode(() -> service.answer("Question", "en"), code);
        }
        doThrow(new IllegalStateException("sensitive provider details")).when(model).generate(anyString(), anyString());
        assertThatThrownBy(() -> service.answer("Question", "en")).isInstanceOfSatisfying(AssistantException.class, error -> {
            assertThat(error.code()).isEqualTo(AssistantException.Code.INTERNAL_ERROR);
            assertThat(error.getMessage()).isEqualTo("INTERNAL_ERROR");
            assertThat(error.getCause()).isNull();
        });
    }

    @Test
    void eachCallContainsOnlyItsQuestionAndLocale() {
        when(model.generate(anyString(), anyString())).thenReturn(
                "{\"status\":\"insufficient_knowledge\",\"answer\":\"Unknown\",\"sourceIds\":[]}");
        service.answer("First question", "pt");
        service.answer("Second question", "en");
        var requests = ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).generate(anyString(), requests.capture());
        assertThat(requests.getAllValues().get(1)).doesNotContain("First question", "history");
        assertThat(AssistantJson.MAPPER.readTree(requests.getValue()).propertyNames())
                .isEqualTo(Set.of("question", "locale"));
    }

    private static void assertCode(Runnable call, AssistantException.Code code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AssistantException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
