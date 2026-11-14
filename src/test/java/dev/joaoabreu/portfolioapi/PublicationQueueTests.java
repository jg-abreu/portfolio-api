package dev.joaoabreu.portfolioapi;

import dev.joaoabreu.portfolioapi.publication.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"portfolio.github.client-id=test-client", "portfolio.github.client-secret=test-secret",
        "portfolio.github.redirect-uri=http://localhost/login/oauth2/code/github"})
class PublicationQueueTests {
    @Autowired PublicationRequests requests;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    @BeforeEach
    void reset() {
        jdbc.sql("UPDATE publication_requests SET requested_generation=0, dispatched_generation=0, lease_token=NULL, lease_until=NULL, next_attempt_at=CURRENT_TIMESTAMP, attempts=0, last_dispatched_at=NULL").update();
    }
    void changed() { new TransactionTemplate(manager).executeWithoutResult(status -> requests.changed()); }

    @Test
    void coalescesCommittedChangesAndAcknowledgesOnlyTheClaimedGeneration() {
        changed(); changed();
        var first = requests.claim().orElseThrow();
        assertEquals(2, first.generation());
        assertTrue(requests.claim().isEmpty());
        changed();
        requests.accepted(first);
        assertEquals(2, requests.status(true).dispatchedGeneration());
        assertEquals(3, requests.status(true).requestedGeneration());
        var second = requests.claim().orElseThrow();
        requests.accepted(second);
        assertTrue(requests.claim().isEmpty());
        assertEquals("DISPATCHED", requests.status(true).state());
    }

    @Test
    void rolledBackChangesDoNotRequestABuild() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            requests.changed(); throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(0, requests.status(false).requestedGeneration());
        assertTrue(requests.claim().isEmpty());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, requests::changed);
    }

    @Test
    void concurrentWorkersCannotClaimTheSameLease() throws Exception {
        changed();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { gate.await(); return requests.claim(); });
            var b = executor.submit(() -> { gate.await(); return requests.claim(); });
            gate.countDown();
            assertEquals(1, (a.get(5, java.util.concurrent.TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (b.get(5, java.util.concurrent.TimeUnit.SECONDS).isPresent() ? 1 : 0));
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedButOldWorkerCannotAcknowledgeOrReleaseIt() {
        changed();
        var old = requests.claim().orElseThrow();
        jdbc.sql("UPDATE publication_requests SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second'").update();
        var fresh = requests.claim().orElseThrow();
        assertNotEquals(old.token(), fresh.token());
        requests.accepted(old); requests.failed(old);
        assertEquals(0, requests.status(true).dispatchedGeneration());
        assertEquals("DISPATCHING", requests.status(true).state());
        requests.accepted(fresh);
        assertEquals(1, requests.status(true).dispatchedGeneration());
    }

    @Test
    void networkFailureKeepsTheQueueAndRetriesAfterBackoff() throws Exception {
        changed();
        var workflow = mock(GitHubWorkflow.class);
        doThrow(new IllegalStateException("synthetic provider failure")).doNothing().when(workflow).dispatch();
        var worker = new PublicationWorker(requests, workflow);
        worker.poll();
        assertEquals(0, requests.status(true).dispatchedGeneration());
        assertEquals(1, requests.status(true).attempts());
        assertTrue(requests.status(true).nextAttemptAt().isAfter(java.time.Instant.now().plusSeconds(200)));
        worker.poll();
        verify(workflow, times(1)).dispatch();
        jdbc.sql("UPDATE publication_requests SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second'").update();
        worker.poll();
        verify(workflow, times(2)).dispatch();
        assertEquals(1, requests.status(true).dispatchedGeneration());
        assertEquals(0, requests.status(true).attempts());
    }

    @Test
    void statusIsPrivateNoStoreDisabledByDefaultAndNeverClaimsDeployment() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/admin/publication-status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/publication-status").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
        changed();
        mvc.perform(get("/api/admin/publication-status").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.state").value("DISABLED"))
                .andExpect(jsonPath("$.requestedGeneration").value(1)).andExpect(jsonPath("$.deployed").doesNotExist());
        assertTrue(context.getBeansOfType(PublicationWorker.class).isEmpty());
        assertTrue(context.getBeansOfType(GitHubWorkflow.class).isEmpty());
    }
}
