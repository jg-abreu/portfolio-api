package dev.joaoabreu.portfolioapi.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("portfolio.gemini")
public record GeminiProperties(@DefaultValue("gemini-3.1-flash-lite") String model) {}
