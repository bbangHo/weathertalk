package kr.weathertalk.member.internal.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.weathertalk.member.internal.AuthService;
import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.member.internal.config.MemberPropertiesConfig;

/**
 * 개발 전용 로그인은 local·staging·test에서만 등록되고 운영(prod)에는 등록되지 않는다
 * (implementation-plan M1 완료 조건, contracts.md §4 개발용 기능, legacy 01-#21).
 * 운영·스테이징은 토큰 서명 비밀값을 환경 변수로 반드시 받는다(없으면 기동 실패).
 */
class DevelopmentOnlyRegistrationTests {

	final ApplicationContextRunner devBeans = new ApplicationContextRunner()
		.withBean(AuthService.class, () -> mock(AuthService.class))
		.withUserConfiguration(DevLoginController.class, DevProviderCredentialVerifier.class);

	final ApplicationContextRunner memberProperties = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(MemberPropertiesConfig.class);

	@org.junit.jupiter.api.Test
	void legacy01_21_devLoginIsNotRegisteredInProd() {
		devBeans.withPropertyValues("spring.profiles.active=prod").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(DevLoginController.class);
			assertThat(context).doesNotHaveBean(DevProviderCredentialVerifier.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "local", "staging", "test" })
	void devLoginIsRegisteredInDevelopmentProfiles(String profile) {
		devBeans.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
			assertThat(context).hasSingleBean(DevLoginController.class);
			assertThat(context).hasSingleBean(DevProviderCredentialVerifier.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "prod", "staging" })
	void tokenSecretIsRequiredOutsideLocalAndTest(String profile) {
		memberProperties.withPropertyValues("spring.profiles.active=" + profile)
			.run(context -> assertThat(context).hasFailed());
		memberProperties
			.withPropertyValues("spring.profiles.active=" + profile,
					"member.token.secret=operations-provided-secret-0123456789abcdef")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@org.junit.jupiter.api.Test
	void localAndTestUseDevelopmentDefaultSecret() {
		memberProperties.run(context -> assertThat(context.getBean(MemberProperties.class).token().secret())
			.startsWith("dev-only"));
	}

}
