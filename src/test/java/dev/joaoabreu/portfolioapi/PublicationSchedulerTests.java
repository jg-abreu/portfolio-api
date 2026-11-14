package dev.joaoabreu.portfolioapi;

import dev.joaoabreu.portfolioapi.publication.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"portfolio.publication.enabled=true", "portfolio.publication.initial-delay=100ms",
        "portfolio.publication.poll-delay=100ms", "portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"})
class PublicationSchedulerTests {
    @Autowired PublicationRequests requests;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean GitHubWorkflow workflow;
    @Test
    void springDispatchesCommittedRequestWithoutCallingWorkerManually() throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(status -> requests.changed());
        verify(workflow, timeout(5000).times(1)).dispatch();
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (requests.status(true).dispatchedGeneration() != 1 && System.nanoTime() < end) Thread.sleep(20);
        assertEquals(1, requests.status(true).dispatchedGeneration());
        assertEquals("DISPATCHED", requests.status(true).state());
    }
}
