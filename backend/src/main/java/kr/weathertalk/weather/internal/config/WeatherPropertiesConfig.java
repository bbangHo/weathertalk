package kr.weathertalk.weather.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.weathertalk.weather.internal.conversion.DayNightJudge;
import kr.weathertalk.weather.internal.conversion.FeelsLikeCalculator;
import kr.weathertalk.weather.internal.conversion.RainPhraseCalculator;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ WeatherProperties.class, WeatherSourceProperties.class })
public class WeatherPropertiesConfig {

	@Bean
	FeelsLikeCalculator feelsLikeCalculator(WeatherProperties properties) {
		return new FeelsLikeCalculator(properties.feelsLike());
	}

	@Bean
	RainPhraseCalculator rainPhraseCalculator(WeatherProperties properties) {
		return new RainPhraseCalculator(properties.rainPhrase());
	}

	@Bean
	DayNightJudge dayNightJudge(WeatherProperties properties) {
		return new DayNightJudge(properties.dayNight());
	}

}
