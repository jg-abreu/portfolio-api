package dev.joaoabreu.portfolioapi.publication;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicationController {
    private final PublicationRequests requests;
    private final boolean enabled;
    public PublicationController(PublicationRequests requests, @Value("${portfolio.publication.enabled:false}") boolean enabled) {
        this.requests = requests; this.enabled = enabled;
    }
    @GetMapping("/api/admin/publication-status")
    public ResponseEntity<PublicationRequests.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.status(enabled));
    }
}
