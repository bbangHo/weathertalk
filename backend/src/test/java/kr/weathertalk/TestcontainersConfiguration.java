package kr.weathertalk;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String S3_ACCESS_KEY = "test-access-key";
	public static final String S3_SECRET_KEY = "test-secret-key-0123456789";
	public static final String S3_BUCKET = "test-community-images";

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

	/**
	 * 사진 객체 저장소 — 운영과 같은 SeaweedFS(S3 게이트웨이)를 띄운다. S3 호환 대체품(MinIO 등)이 아니라 실물을 쓰는 이유:
	 * 사전 서명 검증·path-style·체크섬 처리 같은 호환성 차이가 바로 이 모듈이 의존하는 부분이라서다.
	 * 관리자 신원은 AWS_* 환경 변수로 만든다(로컬 compose와 같은 방식). 서명 검증이 켜진 상태로 테스트한다.
	 */
	@Bean
	GenericContainer<?> seaweedfsContainer() {
		return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
			.withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
			.withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
			.withCommand("server", "-dir=/data", "-s3", "-master.volumeSizeLimitMB=64", "-volume.max=8")
			.withExposedPorts(8333)
			.waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*", 1))
			.withStartupTimeout(Duration.ofMinutes(2));
	}

	@Bean
	DynamicPropertyRegistrar seaweedfsProperties(@Qualifier("seaweedfsContainer") GenericContainer<?> seaweedfsContainer) {
		return registry -> {
			java.util.function.Supplier<Object> endpoint = () -> "http://" + seaweedfsContainer.getHost() + ":"
					+ seaweedfsContainer.getMappedPort(8333);
			registry.add("community.storage.endpoint", endpoint);
			registry.add("community.storage.public-endpoint", endpoint);
			registry.add("community.storage.region", () -> "us-east-1");
			registry.add("community.storage.bucket", () -> S3_BUCKET);
			registry.add("community.storage.access-key", () -> S3_ACCESS_KEY);
			registry.add("community.storage.secret-key", () -> S3_SECRET_KEY);
		};
	}

}
