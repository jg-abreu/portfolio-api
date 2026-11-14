package dev.joaoabreu.portfolioapi.assistant;

@FunctionalInterface
public interface AssistantModel {
    String generate(String systemInstruction, String requestJson);
}
