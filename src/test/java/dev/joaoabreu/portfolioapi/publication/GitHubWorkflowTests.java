package dev.joaoabreu.portfolioapi.publication;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubWorkflowTests {
    @Test
    void sendsOnlyTheDedicatedV2WorkflowAndRefWithServerSideAuthentication() throws Exception {
        var workflow = new GitHubWorkflow("synthetic-token", request -> {
            assertEquals("https://api.github.com/repos/jg-abreu/portfolio/actions/workflows/publication-v2.yml/dispatches", request.uri().toString());
            assertEquals("POST", request.method());
            assertEquals("Bearer synthetic-token", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("{\"ref\":\"feat/v2\"}", GitHubWorkflow.BODY);
            return 204;
        });
        workflow.dispatch();
        new GitHubWorkflow("synthetic-token", request -> 200).dispatch();
    }
    @Test
    void missingCredentialsAndRedirectsOrProviderErrorsCannotAcknowledgeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> new GitHubWorkflow("", request -> fail("must not send")));
        for (int code : List.of(301, 400, 401, 403, 404, 429, 500))
            assertThrows(Exception.class, () -> new GitHubWorkflow("synthetic-token", request -> code).dispatch());
    }
}
