package dev.joaoabreu.portfolioapi.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AssistantRateLimiterTests {
    private final AtomicLong time = new AtomicLong(500);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(time.get()); }
    };

    @Test
    void perClientLimitExpiresAtWindowBoundaryWithoutSleeping() {
        var limiter = limiter(2, 10, 10);
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isEqualTo(60);
        assertThat(limiter.acquire("b")).isZero();
        time.set(59999);
        assertThat(limiter.acquire("a")).isEqualTo(1);
        time.set(60000);
        assertThat(limiter.acquire("a")).isZero();
    }

    @Test
    void globalLimitAndBoundedClientRegistryCannotBeBypassedByNewAddresses() {
        var global = limiter(10, 2, 10);
        assertThat(global.acquire("a")).isZero();
        assertThat(global.acquire("b")).isZero();
        assertThat(global.acquire("c")).isPositive();
        var bounded = limiter(10, 100, 1);
        assertThat(bounded.acquire("a")).isZero();
        assertThat(bounded.acquire("b")).isPositive();
        assertThat(bounded.acquire("a")).isZero();
        time.set(60000);
        assertThat(bounded.acquire("b")).isZero();
    }

    @Test
    void concurrentRequestsCannotExceedQuota() throws Exception {
        var limiter = limiter(7, 100, 10);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> executor.submit(() -> limiter.acquire("a") == 0)).toList();
            int accepted = 0;
            for (var task : tasks) if (task.get()) accepted++;
            assertThat(accepted).isEqualTo(7);
        }
    }

    @Test
    void invalidLimitsFailConfigurationInsteadOfSilentlyDisablingProtection() {
        assertThatThrownBy(() -> limiter(0, 10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(10, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(10, 10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantRateLimitProperties(1, 1, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AssistantRateLimiter limiter(int perClient, int global, int maxClients) {
        return new AssistantRateLimiter(new AssistantRateLimitProperties(perClient, global, Duration.ofMinutes(1), maxClients), clock);
    }
}
