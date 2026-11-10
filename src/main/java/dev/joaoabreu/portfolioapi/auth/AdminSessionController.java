package dev.joaoabreu.portfolioapi.auth;

import java.security.Principal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminSessionController {
    @GetMapping("/api/auth/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/api/admin/session")
    SessionResponse session(Principal principal) {
        return new SessionResponse(principal.getName());
    }

    public record CsrfResponse(String headerName, String token) {}

    public record SessionResponse(String username) {}
}