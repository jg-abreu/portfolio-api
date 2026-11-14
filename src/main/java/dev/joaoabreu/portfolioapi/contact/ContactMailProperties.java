package dev.joaoabreu.portfolioapi.contact;

import java.util.List;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("portfolio.contact.mail")
public record ContactMailProperties(@NotBlank @Email String from,
        @NotEmpty List<@NotBlank @Email String> recipients) {}
