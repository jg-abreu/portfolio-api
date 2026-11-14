package dev.joaoabreu.portfolioapi.assistant;

import dev.joaoabreu.portfolioapi.auth.GitHubAdminUserService;
import dev.joaoabreu.portfolioapi.auth.SecurityConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AssistantController.class, properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "portfolio.assistant.rate-limit.requests-per-window=2",
        "portfolio.assistant.rate-limit.global-requests-per-window=3",
        "portfolio.assistant.rate-limit.window=1d" })
@Import({SecurityConfiguration.class, GitHubAdminUserService.class, AssistantWebConfiguration.class,
        AssistantRateLimiter.class, AssistantErrorHandler.class})
class AssistantRateLimitWebTests {
    @Autowired MockMvc mvc;
    @MockitoBean AssistantService service;

    @Test
    void configuredPerClientAndGlobalLimitsReturn429AndRetryAfterWithoutCallingService() throws Exception {
        when(service.answer("Hi", "en")).thenReturn(new AssistantResponse("answered", "Answer", "en", List.of(), "v1"));
        for (int i = 0; i < 2; i++) request("192.0.2.1", "198.51.100." + i).andExpect(status().isOk());
        request("192.0.2.1", "198.51.100.99").andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
        request("192.0.2.2", "198.51.100.2").andExpect(status().isOk());
        request("192.0.2.3", "198.51.100.3").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        verify(service, times(3)).answer(anyString(), anyString());
    }

    private org.springframework.test.web.servlet.ResultActions request(String ip, String spoofedForwardedIp) throws Exception {
        return mvc.perform(post(AssistantController.PATH).with(request -> { request.setRemoteAddr(ip); return request; })
                .header("X-Forwarded-For", spoofedForwardedIp).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Hi\",\"locale\":\"en\"}"));
    }
}
