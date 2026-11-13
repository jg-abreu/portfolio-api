package dev.joaoabreu.portfolioapi;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@Import({TestcontainersConfiguration.class, ContactHttpErrorTests.SessionFixture.class})
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github",
        "server.servlet.session.cookie.secure=false", "portfolio.contact.mail.enabled=false",
        "portfolio.contact.submission.enabled=false", "portfolio.contact.retention.enabled=false"
})
class ContactHttpErrorTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void staleHttpUpdateReturnsConflictWithoutLosingTheAdminSession() throws Exception {
        long id = jdbc.sql("""
                INSERT INTO contact_messages (name, email, message)
                VALUES ('HTTP conflict test', 'sender@example.com', 'Synthetic conflict message') RETURNING id
                """).query(Long.class).single();
        try (var client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals(204, send(client, "/__test_session").statusCode());
            var csrf = json.readTree(send(client, "/api/auth/csrf").body());
            String header = csrf.get("headerName").asText();
            String token = csrf.get("token").asText();
            assertEquals(200, patch(client, id, "read", header, token).statusCode());
            assertEquals(409, patch(client, id, "archive", header, token).statusCode());
            assertEquals(403, patch(client, id, "archive", header, "invalid-csrf").statusCode());
            assertEquals(403, send(client, "/error").statusCode());
            assertEquals(200, send(client, "/api/admin/messages/" + id).statusCode());
            assertEquals(404, send(client, "/api/admin/messages/9223372036854775807").statusCode());
            assertEquals(200, send(client, "/api/admin/messages/" + id).statusCode());
            assertTrue(jdbc.sql("SELECT read_at IS NOT NULL AND archived_at IS NULL AND version = 1 FROM contact_messages WHERE id=:id")
                    .param("id", id).query(Boolean.class).single());
        }
        try (var anonymous = HttpClient.newHttpClient()) {
            assertEquals(401, send(anonymous, "/api/admin/messages/" + id).statusCode());
        }
    }

    private HttpResponse<String> send(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(HttpClient client, long id, String action, String header, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/messages/" + id + "/" + action))
                .header("Content-Type", "application/json").header(header, token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"value\":true,\"version\":0}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionFixture {
        @Bean
        FilterRegistrationBean<Filter> testSession() {
            Filter sessionFilter = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                if (!http.getRequestURI().equals("/__test_session")) {
                    chain.doFilter(request, response);
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken("test-admin", null,
                        AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
                http.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        new SecurityContextImpl(authentication));
                ((HttpServletResponse) response).setStatus(204);
            };
            var registration = new FilterRegistrationBean<>(sessionFilter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/__test_session");
            return registration;
        }
    }
}
