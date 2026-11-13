package dev.joaoabreu.portfolioapi.visits;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "portfolio.visits.enabled", havingValue = "true")
@EnableConfigurationProperties(VisitConfiguration.Properties.class)
@EnableScheduling
public class VisitConfiguration {
    @Bean
    Clock visitClock(Environment environment) {
        if (!environment.getProperty("server.forward-headers-strategy", "NONE").equalsIgnoreCase("NONE"))
            throw new IllegalStateException("Visit address resolution requires server.forward-headers-strategy=NONE");
        return Clock.systemUTC();
    }

    public enum IpSource { DIRECT, CLOUDFLARE_PROXY }

    @ConfigurationProperties("portfolio.visits")
    public record Properties(String hmacKey, @DefaultValue("DIRECT") IpSource ipSource,
            List<String> trustedProxies, List<String> cloudflareCidrs) {
        public Properties {
            if (hmacKey == null || hmacKey.isBlank() || hmacKey.length() < 32 || hmacKey.length() > 512)
                throw new IllegalArgumentException("A visit HMAC key of 32 to 512 characters is required");
            trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
            cloudflareCidrs = cloudflareCidrs == null ? List.of() : List.copyOf(cloudflareCidrs);
            if (ipSource == IpSource.CLOUDFLARE_PROXY && (trustedProxies.isEmpty() || cloudflareCidrs.isEmpty()))
                throw new IllegalArgumentException("Cloudflare mode requires explicit proxy and Cloudflare CIDRs");
        }
        @Override public String toString() { return "VisitProperties[ipSource=" + ipSource + ", hmacKey=REDACTED]"; }
    }
}
