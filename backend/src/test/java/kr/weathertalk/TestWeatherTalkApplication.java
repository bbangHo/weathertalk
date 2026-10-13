package kr.weathertalk;

import org.springframework.boot.SpringApplication;

/**
 * Docker Compose 없이 Testcontainers로 로컬 실행: {@code ./gradlew bootTestRun}
 */
public class TestWeatherTalkApplication {

	public static void main(String[] args) {
		SpringApplication.from(WeatherTalkApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
