package dev.joaoabreu.portfolioapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"portfolio.profile-card.enabled=true", "portfolio.profile-card.github-token=",
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"})
class ProfileCardHttpTests {
    @Autowired WebApplicationContext context;
    @Test
    void anonymousGetWorksWithoutCreatingASessionAndWritesRemainProtected() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response = mvc.perform(get("/api/profile-card.svg")).andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml;charset=UTF-8"))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        org.junit.jupiter.api.Assertions.assertNull(response.getRequest().getSession(false));
        mvc.perform(post("/api/profile-card.svg")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/recommendations")).andExpect(status().isUnauthorized());
    }
}
