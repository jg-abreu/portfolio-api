package dev.joaoabreu.portfolioapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "portfolio.github.client-id=test-client",
        "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"
})
class PortfolioApiApplicationTests {

    @Test
    void contextLoads() {
    }
}