package dev.joaoabreu.portfolioapi.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AdminSessionController.class, properties = {
        "portfolio.github.client-id=test-client",
        "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"
})
@Import({SecurityConfiguration.class, GitHubAdminUserService.class, AdminAuthenticationTests.GitHubTestConfiguration.class})
class AdminAuthenticationTests {

    private static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final HttpServer GITHUB = startGitHub();

    @Autowired
    MockMvc mvc;

    @AfterAll
    static void stopGitHub() {
        GITHUB.stop(0);
    }

    @Test
    void adminEntryStartsGithubLogin() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/github"));
    }

    @Test
    void anonymousCannotReadAdminSession() throws Exception {
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfEndpointProvidesTokenWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    void authorizationRequestIncludesStateAndPkceWithoutRepositoryScopes() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "jg-abreu");
        assertFalse(attempt.parameters().get("state").isBlank());
        assertEquals("S256", attempt.parameters().get("code_challenge_method"));
        assertFalse(attempt.parameters().get("code_challenge").isBlank());
        assertFalse(attempt.parameters().containsKey("scope"));
        assertFalse(attempt.parameters().containsKey("client_secret"));
        assertFalse(attempt.parameters().containsKey("code_verifier"));
    }

    @Test
    void correctGithubIdAuthenticatesAndRotatesSessionId() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "jg-abreu");
        String oldId = attempt.session().getId();
        callback(attempt, attempt.parameters().get("state"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin"));
        assertNotEquals(oldId, attempt.session().getId());
        mvc.perform(get("/api/admin/session").session(attempt.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubId").value("265870608"))
                .andExpect(jsonPath("$.username").value("jg-abreu"));
        mvc.perform(get("/admin").session(attempt.session()))
                .andExpect(redirectedUrl("/en/admin/"));
    }

    @Test
    void sameIdStillWorksAfterGithubUsernameChanges() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "renamed-admin");
        callback(attempt, attempt.parameters().get("state")).andExpect(status().isFound());
        mvc.perform(get("/api/admin/session").session(attempt.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("renamed-admin"));
    }

    @Test
    void differentGithubIdIsDeniedEvenWithAdminUsername() throws Exception {
        LoginAttempt attempt = startLogin(42L, "jg-abreu");
        callback(attempt, attempt.parameters().get("state")).andExpect(status().isUnauthorized());
        assertTrue(attempt.session().isInvalid());
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void browserCannotOverrideGithubIdentity() throws Exception {
        LoginAttempt attempt = startLogin(42L, "someone-else");
        mvc.perform(get("/login/oauth2/code/github").session(attempt.session())
                .param("code", attempt.code()).param("state", attempt.parameters().get("state"))
                .param("id", "265870608").param("githubId", "265870608"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedStateIsRejectedBeforeTokenExchange() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "jg-abreu");
        int requestsBefore = TOKEN_REQUESTS.get();
        callback(attempt, "tampered-state").andExpect(status().isUnauthorized());
        assertEquals(requestsBefore, TOKEN_REQUESTS.get());
    }

    @Test
    void unsolicitedCallbackIsRejected() throws Exception {
        int requestsBefore = TOKEN_REQUESTS.get();
        mvc.perform(get("/login/oauth2/code/github").param("code", "invented").param("state", "invented"))
                .andExpect(status().isUnauthorized());
        assertEquals(requestsBefore, TOKEN_REQUESTS.get());
    }

    @Test
    void callbackCannotBeReplayed() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "jg-abreu");
        callback(attempt, attempt.parameters().get("state")).andExpect(status().isFound());
        int requestsBefore = TOKEN_REQUESTS.get();
        callback(attempt, attempt.parameters().get("state")).andExpect(status().isUnauthorized());
        assertEquals(requestsBefore, TOKEN_REQUESTS.get());
        assertTrue(attempt.session().isInvalid());
    }

    @Test
    void logoutRequiresFreshCsrfAndInvalidatesSession() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "jg-abreu");
        CsrfToken oldToken = csrfToken(attempt.session());
        callback(attempt, attempt.parameters().get("state")).andExpect(status().isFound());
        mvc.perform(post("/api/auth/logout").session(attempt.session()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(attempt.session())
                .header(oldToken.getHeaderName(), oldToken.getToken()))
                .andExpect(status().isForbidden());
        CsrfToken newToken = csrfToken(attempt.session());
        mvc.perform(post("/api/auth/logout").session(attempt.session())
                .header(newToken.getHeaderName(), newToken.getToken()))
                .andExpect(status().isNoContent());
        assertTrue(attempt.session().isInvalid());
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordLoginIsNoLongerAvailable() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "admin").param("password", "anything"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAdminCannotAccessUnlistedRoutes() throws Exception {
        LoginAttempt attempt = startLogin(265870608L, "jg-abreu");
        callback(attempt, attempt.parameters().get("state")).andExpect(status().isFound());
        mvc.perform(get("/api/unlisted").session(attempt.session())).andExpect(status().isForbidden());
    }

    private LoginAttempt startLogin(long id, String login) throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().isFound()).andReturn();
        Map<String, String> parameters = form(URI.create(result.getResponse().getRedirectedUrl()).getRawQuery());
        String code = UUID.randomUUID().toString();
        GRANTS.put(code, new Grant(id, login, parameters.get("code_challenge")));
        return new LoginAttempt((MockHttpSession) result.getRequest().getSession(false), parameters, code);
    }

    private ResultActions callback(LoginAttempt attempt, String state) throws Exception {
        return mvc.perform(get("/login/oauth2/code/github").session(attempt.session())
                .with(request -> {
                    request.setRequestedSessionId(attempt.session().getId());
                    request.setRequestedSessionIdValid(true);
                    request.setRequestedSessionIdFromCookie(true);
                    return request;
                })
                .param("code", attempt.code()).param("state", state));
    }

    private CsrfToken csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        return (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
    }

    private static HttpServer startGitHub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                TOKEN_REQUESTS.incrementAndGet();
                Map<String, String> parameters = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String code = parameters.get("code");
                Grant grant = GRANTS.get(code);
                if (grant == null || !grant.challenge().equals(challenge(parameters.get("code_verifier")))) {
                    respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                respond(exchange, 200, "{\"access_token\":\"" + code + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
            });
            server.createContext("/user", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                Grant grant = authorization == null ? null : GRANTS.get(authorization.replace("Bearer ", ""));
                if (grant == null) {
                    respond(exchange, 401, "{}");
                    return;
                }
                respond(exchange, 200, "{\"id\":" + grant.id() + ",\"login\":\"" + grant.login() + "\"}");
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String challenge(String verifier) {
        if (verifier == null) return "";
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> form(String text) {
        return Arrays.stream(text.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Grant(long id, String login, String challenge) {}

    private record LoginAttempt(MockHttpSession session, Map<String, String> parameters, String code) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class GitHubTestConfiguration {

        @Bean
        @Primary
        ClientRegistrationRepository simulatedGithub() {
            String baseUrl = "http://127.0.0.1:" + GITHUB.getAddress().getPort();
            ClientRegistration github = ClientRegistration.withRegistrationId("github")
                    .clientId("test-client").clientSecret("test-secret")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/login/oauth2/code/github")
                    .authorizationUri(baseUrl + "/authorize")
                    .tokenUri(baseUrl + "/token")
                    .userInfoUri(baseUrl + "/user")
                    .userNameAttributeName("id")
                    .clientName("GitHub").build();
            return new InMemoryClientRegistrationRepository(github);
        }
    }
}