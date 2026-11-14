package dev.joaoabreu.portfolioapi.projects;

import java.util.List;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Converter
public class ProjectProfilesConverter implements AttributeConverter<List<ProjectDraftRequest.Profile>, String> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<ProjectDraftRequest.Profile> profiles) {
        return JSON.writeValueAsString(profiles == null ? List.of() : profiles);
    }

    @Override
    public List<ProjectDraftRequest.Profile> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.copyOf(JSON.readValue(value, new TypeReference<List<ProjectDraftRequest.Profile>>() {}));
    }
}
