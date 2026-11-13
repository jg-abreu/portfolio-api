package dev.joaoabreu.portfolioapi.publication;

import org.springframework.scheduling.annotation.Scheduled;

public class PublicationWorker {
    private final PublicationRequests requests;
    private final GitHubWorkflow workflow;
    public PublicationWorker(PublicationRequests requests, GitHubWorkflow workflow) {
        this.requests = requests; this.workflow = workflow;
    }
    @Scheduled(initialDelayString = "${portfolio.publication.initial-delay:30s}", fixedDelayString = "${portfolio.publication.poll-delay:30s}")
    public void poll() {
        requests.claim().ifPresent(claim -> {
            try { workflow.dispatch(); requests.accepted(claim); }
            catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                requests.failed(claim);
            }
        });
    }
}
