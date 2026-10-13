package kr.weathertalk.weather;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.config.WeatherPropertiesConfig;

/** 단위 테스트용: application.yml 초기값 그대로의 {@link WeatherProperties}. */
public final class WeatherPropertiesFixture {

	private static final AtomicReference<WeatherProperties> DEFAULTS = new AtomicReference<>();

	private WeatherPropertiesFixture() {
	}

	public static WeatherProperties defaults() {
		if (DEFAULTS.get() == null) {
			new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(WeatherPropertiesConfig.class)
				.run(context -> DEFAULTS.set(context.getBean(WeatherProperties.class)));
		}
		return DEFAULTS.get();
	}

}
