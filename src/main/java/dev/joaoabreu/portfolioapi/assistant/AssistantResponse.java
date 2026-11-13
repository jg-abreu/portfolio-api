package dev.joaoabreu.portfolioapi.assistant;

import java.util.List;

public record AssistantResponse(String status, String answer, String locale,
        List<Source> sources, String knowledgeVersion) {
    public AssistantResponse { sources = List.copyOf(sources); }

    public record Source(String id, String title, String url) {}
}
