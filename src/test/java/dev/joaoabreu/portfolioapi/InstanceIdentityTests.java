package dev.joaoabreu.portfolioapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import dev.joaoabreu.portfolioapi.instance.InstanceIdentity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "portfolio.instance.name=B",
        "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github" })
class InstanceIdentityTests {
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(InstanceIdentity.HeaderFilter.class)).apply(springSecurity()).build();
    }

    @Test
    void anonymousVisitorsLearnWhichInstanceAnsweredWithoutCaching() throws Exception {
        mvc.perform(get("/api/instance").with(anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.instance").value("B"))
                .andExpect(header().string("X-Instance", "B"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void everyResponseNamesTheInstanceEvenWhenAccessIsDenied() throws Exception {
        mvc.perform(get("/api/admin/messages").with(anonymous())).andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Instance", "B"));
        mvc.perform(get("/api/projects").with(anonymous())).andExpect(header().string("X-Instance", "B"));
    }

    @Test
    void rejectsUnsafeInstanceNames() {
        for (String name : new String[] { "", "a b", "x".repeat(17), "A\r\nSet-Cookie: x" })
            assertThrows(IllegalArgumentException.class, () -> new InstanceIdentity(name));
    }
}
