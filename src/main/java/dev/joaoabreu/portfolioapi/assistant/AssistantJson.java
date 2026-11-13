package dev.joaoabreu.portfolioapi.assistant;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class AssistantJson {
    // Isolated mapper: strict model/resource parsing must not change the application's mapper.
    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AssistantJson() {}
}
