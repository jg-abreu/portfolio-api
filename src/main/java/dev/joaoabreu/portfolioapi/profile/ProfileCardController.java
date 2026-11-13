package dev.joaoabreu.portfolioapi.profile;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "portfolio.profile-card.enabled", havingValue = "true")
public class ProfileCardController {
    private final ProfileCardCache cache;
    public ProfileCardController(ProfileCardCache cache) { this.cache = cache; }

    @GetMapping(value = "/api/profile-card.svg", produces = "image/svg+xml")
    public ResponseEntity<String> card(WebRequest request) {
        var card = cache.current();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"));
        headers.setCacheControl("public, max-age=300, s-maxage=300, must-revalidate");
        headers.setETag(card.etag());
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("X-Content-Type-Options", "nosniff");
        if (request.checkNotModified(card.etag())) return ResponseEntity.status(304).headers(headers).build();
        return ResponseEntity.ok().headers(headers).body(card.svg());
    }
}
