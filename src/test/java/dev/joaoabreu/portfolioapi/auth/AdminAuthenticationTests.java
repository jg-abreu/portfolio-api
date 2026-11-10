package dev.joaoabreu.portfolioapi.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminSessionController.class)
@Import(SecurityConfiguration.class)
class AdminAuthenticationTests {

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void adminProperties(DynamicPropertyRegistry registry) {
        String hash = new BCryptPasswordEncoder().encode("test-password-only");
        registry.add("portfolio.admin.username", () -> "admin");
        registry.add("portfolio.admin.password-hash", () -> hash);
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
    void loginWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "admin")
                .param("password", "test-password-only"))
                .andExpect(status().isForbidden());
    }

    @Test
    void incorrectPasswordDoesNotCreateAuthenticatedSession() throws Exception {
        CsrfSession csrf = csrfSession(null);
        login(csrf, "admin", "incorrect").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/session").session(csrf.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserHasSameFailureResponse() throws Exception {
        login(csrfSession(null), "unknown", "test-password-only")
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void successfulLoginRotatesSessionIdAndAllowsAdminAccess() throws Exception {
        CsrfSession csrf = csrfSession(null);
        String previousId = csrf.session().getId();
        login(csrf, "admin", "test-password-only").andExpect(status().isNoContent());
        assertNotEquals(previousId, csrf.session().getId());
        mvc.perform(get("/api/admin/session").session(csrf.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesSession() throws Exception {
        CsrfSession csrf = csrfSession(null);
        login(csrf, "admin", "test-password-only").andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").session(csrf.session()))
                .andExpect(status().isForbidden());
        CsrfSession authenticated = csrfSession(csrf.session());
        mvc.perform(post("/api/auth/logout").session(authenticated.session())
                .header(authenticated.token().getHeaderName(), authenticated.token().getToken()))
                .andExpect(status().isNoContent());
        assertTrue(authenticated.session().isInvalid());
        mvc.perform(get("/api/admin/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void oldCsrfTokenCannotBeUsedAfterLogin() throws Exception {
        CsrfSession csrf = csrfSession(null);
        login(csrf, "admin", "test-password-only").andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").session(csrf.session())
                .header(csrf.token().getHeaderName(), csrf.token().getToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedAdminCannotAccessUnlistedRoutes() throws Exception {
        CsrfSession csrf = csrfSession(null);
        login(csrf, "admin", "test-password-only").andExpect(status().isNoContent());
        mvc.perform(get("/api/unlisted").session(csrf.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void configurationRejectsPlaintextPasswordsAndEmptyUsername() {
        SecurityConfiguration configuration = new SecurityConfiguration();
        assertThrows(IllegalArgumentException.class, () -> configuration.adminUsers("admin", "plain-text"));
        assertThrows(IllegalArgumentException.class, () -> configuration.adminUsers(" ", "plain-text"));
    }

    private CsrfSession csrfSession(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new CsrfSession((MockHttpSession) result.getRequest().getSession(false),
                (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName()));
    }

    private org.springframework.test.web.servlet.ResultActions login(
            CsrfSession csrf, String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").session(csrf.session())
                .with(request -> {
                    request.setRequestedSessionId(csrf.session().getId());
                    request.setRequestedSessionIdValid(true);
                    request.setRequestedSessionIdFromCookie(true);
                    return request;
                })
                .header(csrf.token().getHeaderName(), csrf.token().getToken())
                .param("username", username).param("password", password));
    }

    private record CsrfSession(MockHttpSession session, CsrfToken token) {}
}