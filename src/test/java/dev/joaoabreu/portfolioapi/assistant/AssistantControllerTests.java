package dev.joaoabreu.portfolioapi.assistant;

import dev.joaoabreu.portfolioapi.auth.GitHubAdminUserService;
import dev.joaoabreu.portfolioapi.auth.SecurityConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AssistantController.class, properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "portfolio.assistant.rate-limit.requests-per-window=1000",
        "portfolio.assistant.rate-limit.global-requests-per-window=10000" })
@Import({SecurityConfiguration.class, GitHubAdminUserService.class, AssistantWebConfiguration.class,
        AssistantRateLimiter.class, AssistantErrorHandler.class})
class AssistantControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean AssistantService service;

    @Test
    void anonymousPostWorksWithoutCsrfAndPreservesV1ShapeInBothLocales() throws Exception {
        for (String locale : List.of("pt", "en")) {
            when(service.answer("Question", locale)).thenReturn(new AssistantResponse("answered", "Plain answer", locale,
                    List.of(new AssistantResponse.Source("education", "Education", null)), "2026-11-12.1"));
            var result = mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"  Question  \",\"locale\":\"" + locale + "\"}"))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("answered")).andExpect(jsonPath("$.locale").value(locale))
                    .andExpect(jsonPath("$.answer").value("Plain answer"))
                    .andExpect(jsonPath("$.sources[0].id").value("education"))
                    .andExpect(jsonPath("$.knowledgeVersion").value("2026-11-12.1"))
                    .andExpect(jsonPath("$.sourceIds").doesNotExist()).andReturn();
            assertThat(AssistantJson.MAPPER.readTree(result.getResponse().getContentAsString())
                    .get("sources").get(0).get("url").isNull()).isTrue();
            verify(service).answer("Question", locale);
        }
    }

    @Test
    void insufficientKnowledgeIsSuccessNotTechnicalFailure() throws Exception {
        when(service.answer("Salary?", "pt")).thenReturn(new AssistantResponse("insufficient_knowledge",
                AssistantResourcesTests.resources().requireAvailable().fallbackAnswers().get("pt"), "pt", List.of(), "2026-11-12.1"));
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Salary?\",\"locale\":\"pt\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("insufficient_knowledge"))
                .andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void malformedJsonExtraFieldsDuplicateKeysAndWrongTypesNeverCallService() throws Exception {
        for (String json : new String[] {"", "{", "[]", "null", "{} {}", "{}", "{\"question\":42,\"locale\":\"pt\"}",
                "{\"question\":\"Hi\",\"locale\":true}", "{\"question\":null,\"locale\":\"pt\"}",
                "{\"question\":\"Hi\",\"locale\":\"pt\",\"history\":[]}",
                "{\"question\":\"Hi\",\"question\":\"Other\",\"locale\":\"pt\"}",
                "{\"question\":\"Hi\",\"locale\":\"PT\"}", "{\"question\":\"  \",\"locale\":\"pt\"}" }) {
            mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void returnsStableFieldErrorsWithoutEchoingVisitorInput() throws Exception {
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"unsupported-visitor-value\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("question"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[1].code").value("UNSUPPORTED_LOCALE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsupported-visitor-value"))));
    }

    @Test
    void acceptsTwoThousandUnicodeCodePointsAndRejectsLongerQuestion() throws Exception {
        String question = "😀".repeat(2000);
        when(service.answer(question, "en")).thenReturn(new AssistantResponse("answered", "Answer", "en", List.of(), "v1"));
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(AssistantJson.MAPPER.writeValueAsString(java.util.Map.of("question", question, "locale", "en"))))
                .andExpect(status().isOk());
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(AssistantJson.MAPPER.writeValueAsString(java.util.Map.of("question", question + "x", "locale", "en"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_LENGTH"));
        verify(service, times(1)).answer(anyString(), anyString());
    }

    @Test
    void allowsExactlySixteenKiBAndRejectsAnExtraByte() throws Exception {
        String json = "{\"question\":\"Hi\",\"locale\":\"en\"}";
        String boundary = json + " ".repeat(16384 - json.getBytes(StandardCharsets.UTF_8).length);
        when(service.answer("Hi", "en")).thenReturn(new AssistantResponse("answered", "Answer", "en", List.of(), "v1"));
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON).content(boundary))
                .andExpect(status().isOk());
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON).content(boundary + " "))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(service, times(1)).answer(anyString(), anyString());
    }

    @Test
    void rejectsMissingWrongMediaTypeEncodingAndMalformedUtf8() throws Exception {
        for (String type : List.of("text/plain", "application/xml", "application/json;charset=UTF-16"))
            mvc.perform(post(AssistantController.PATH).contentType(type).content("{}"))
                    .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(post(AssistantController.PATH).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON).header("Content-Encoding", "gzip").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON).content(new byte[] {(byte) 0xc3, 0x28}))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void technicalErrorsRemainProblemJsonWithNoInternalDetails() throws Exception {
        for (var code : List.of(AssistantException.Code.ASSISTANT_UNAVAILABLE, AssistantException.Code.ASSISTANT_TIMEOUT,
                AssistantException.Code.INVALID_ASSISTANT_RESPONSE, AssistantException.Code.INTERNAL_ERROR)) {
            doThrow(new AssistantException(code)).when(service).answer("Hi", "en");
            mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Hi\",\"locale\":\"en\"}"))
                    .andExpect(status().is(code.httpStatus())).andExpect(jsonPath("$.type").value("about:blank"))
                    .andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.status").value(code.httpStatus()))
                    .andExpect(jsonPath("$.instance").value(AssistantController.PATH))
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        doThrow(new IllegalStateException("private provider details")).when(service).answer("Hi", "en");
        mvc.perform(post(AssistantController.PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Hi\",\"locale\":\"en\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private provider details"))));
    }

    @Test
    void csrfExceptionDoesNotApplyToAdministrativeOrOtherRoutes() throws Exception {
        mvc.perform(post("/api/admin/projects").with(oauth2Login().authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").with(oauth2Login().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
        mvc.perform(post(AssistantController.PATH + "/extra").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(AssistantController.PATH).with(oauth2Login().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
