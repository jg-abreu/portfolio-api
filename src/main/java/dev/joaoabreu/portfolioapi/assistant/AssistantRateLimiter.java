package dev.joaoabreu.portfolioapi.assistant;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(AssistantRateLimitProperties.class)
public class AssistantRateLimiter {
    private final AssistantRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Integer> clients = new HashMap<>();
    private long currentWindow = Long.MIN_VALUE;
    private int total;

    @Autowired
    public AssistantRateLimiter(AssistantRateLimitProperties properties) { this(properties, Clock.systemUTC()); }

    AssistantRateLimiter(AssistantRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Returns zero when accepted, otherwise the positive Retry-After value in seconds. */
    synchronized long acquire(String client) {
        long windowMillis = properties.window().toMillis();
        long now = clock.millis();
        long window = Math.floorDiv(now, windowMillis);
        if (window != currentWindow) {
            currentWindow = window;
            total = 0;
            clients.clear();
        }
        int count = clients.getOrDefault(client, 0);
        if (total >= properties.globalRequestsPerWindow() || count >= properties.requestsPerWindow()
                || (!clients.containsKey(client) && clients.size() >= properties.maxClients()))
            return Math.max(1, (windowMillis - Math.floorMod(now, windowMillis) + 999) / 1000);
        clients.put(client, count + 1);
        total++;
        return 0;
    }
}
