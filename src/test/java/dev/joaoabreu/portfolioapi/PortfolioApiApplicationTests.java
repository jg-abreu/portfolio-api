package dev.joaoabreu.portfolioapi;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PortfolioApiApplicationTests {

    @DynamicPropertySource
    static void adminProperties(DynamicPropertyRegistry registry) {
        String hash = new BCryptPasswordEncoder().encode("test-password-only");
        registry.add("portfolio.admin.username", () -> "admin");
        registry.add("portfolio.admin.password-hash", () -> hash);
    }

	@Test
	void contextLoads() {
	}

}
