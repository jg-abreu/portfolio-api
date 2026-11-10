package dev.joaoabreu.portfolioapi.auth;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminSessionController {

    @GetMapping("/admin")
    ResponseEntity<Void> admin(@AuthenticationPrincipal OAuth2User user) {
        String destination = user == null ? "/oauth2/authorization/github" : "/api/admin/session";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(destination)).build();
    }

    @GetMapping("/api/auth/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/api/admin/session")
    SessionResponse session(@AuthenticationPrincipal OAuth2User user) {
        return new SessionResponse(user.getName(), user.getAttribute("login"));
    }

    public record CsrfResponse(String headerName, String token) {}

    public record SessionResponse(String githubId, String username) {}
}