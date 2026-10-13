package kr.weathertalk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계 검증 (ADR 0002, ADR 0010). 순환 의존, 허용되지 않은 의존, 다른 모듈의 internal 접근이 있으면 실패한다.
 */
class ModularityTests {

	static final ApplicationModules modules = ApplicationModules.of(WeatherTalkApplication.class);

	@Test
	void verifiesModuleBoundaries() {
		modules.verify();
	}

	@Test
	void hasExactlyTheModulesDefinedInContracts() {
		assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
			.containsExactlyInAnyOrder("shared", "member", "neighborhood", "weather", "feeling", "outfit",
					"community", "notification", "home");
	}

	@Test
	void writesModuleDocumentation() {
		// build/spring-modulith-docs 에 모듈 다이어그램(PlantUML)과 모듈 캔버스를 생성한다.
		new Documenter(modules).writeDocumentation();
	}

}
