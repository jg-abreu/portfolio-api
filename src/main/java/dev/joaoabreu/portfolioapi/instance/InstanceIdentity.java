package dev.joaoabreu.portfolioapi.instance;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
public class InstanceIdentity {
    public static final String HEADER = "X-Instance";
    private final String name;

    public InstanceIdentity(@Value("${portfolio.instance.name:local}") String name) {
        if (name == null || !name.matches("[A-Za-z0-9-]{1,16}")) throw new IllegalArgumentException("Invalid instance name");
        this.name = name;
    }

    public String name() { return name; }

    @GetMapping("/api/instance")
    ResponseEntity<Map<String, String>> instance() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("instance", name));
    }

    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class HeaderFilter extends OncePerRequestFilter {
        private final InstanceIdentity identity;

        HeaderFilter(InstanceIdentity identity) { this.identity = identity; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(HEADER, identity.name());
            chain.doFilter(request, response);
        }
    }
}
