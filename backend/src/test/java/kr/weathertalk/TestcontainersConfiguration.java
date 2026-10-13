package kr.weathertalk;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// PostGIS 이미지는 첫 기동 때 확장을 설치하느라 기본 대기 시간(60초)을 넘길 수 있다.
		return new PostgreSQLContainer(
				DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
			.withStartupTimeout(Duration.ofMinutes(3));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
	}

}
