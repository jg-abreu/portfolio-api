package dev.joaoabreu.portfolioapi.visits;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "portfolio.visits.enabled", havingValue = "true")
public class VisitController {
    private final VisitCounterService counter;
    private final VisitClientAddress addresses;
    private final byte[] key;

    public VisitController(VisitCounterService counter, VisitClientAddress addresses, VisitConfiguration.Properties properties) {
        this.counter = counter;
        this.addresses = addresses;
        this.key = properties.hmacKey().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/visits")
    ResponseEntity<Total> total() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Total(counter.total())); }

    @PostMapping("/api/visits")
    ResponseEntity<VisitCounterService.Receipt> register(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank() || agent.length() > 1024
                || agent.toLowerCase(Locale.ROOT).matches(".*(bot|crawl|spider|headless|lighthouse|curl|wget|python-requests).*"))
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VisitCounterService.Receipt(counter.total(), false));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(counter.register(mac.doFinal(addresses.resolve(request))));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Visit hashing unavailable"); }
    }

    public record Total(long total) {}
}
