package kr.weathertalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.config.MemberPropertiesConfig;

/**
 * 비밀값은 .env(KEY=값, properties 형식)로 넣을 수 있다: application.yml이 저장소 루트·backend의 .env를 선택적으로 읽고,
 * yml의 ${변수명} 자리표시자가 그 값으로 채워진다.
 */
class EnvFileImportTests {

	@Test
	void applicationYmlImportsDotEnvFromRepositoryRootAndBackend() throws IOException {
		String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

		assertThat(yml).contains("optional:file:../.env[.properties]", "optional:file:.env[.properties]");
	}

	@Test
	void placeholdersResolveFromDotEnvFile(@TempDir Path dir) throws IOException {
		// 실제 .env나 OS 환경 변수가 같은 키를 주고 있으면 어느 값이 이기는지가 이 검증과 섞이므로 건너뛴다.
		assumeTrue(System.getenv("MEMBER_TOKEN_SECRET") == null && Files.notExists(Path.of("../.env"))
				&& Files.notExists(Path.of(".env")));
		Path env = dir.resolve(".env");
		Files.writeString(env, """
				# 주석
				MEMBER_TOKEN_SECRET=from-dot-env-file-0123456789abcdef0123
				WEATHER_KMA_SERVICE_KEY=
				""", StandardCharsets.UTF_8);

		new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(MemberPropertiesConfig.class)
			.withPropertyValues("spring.config.import=optional:file:" + env.toAbsolutePath().toString().replace(java.io.File.separatorChar, '/')
					+ "[.properties]")
			.run(context -> assertThat(context.getBean(MemberProperties.class).token().secret())
				.isEqualTo("from-dot-env-file-0123456789abcdef0123"));
	}

}
