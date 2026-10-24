package dev.joaoabreu.portfolioapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	// Same image as compose.yaml, so tests run against the database used in development.
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

}
