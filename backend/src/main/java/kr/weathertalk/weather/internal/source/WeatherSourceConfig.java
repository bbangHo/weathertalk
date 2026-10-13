package kr.weathertalk.weather.internal.source;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;

/** 외부 원천 어댑터 등록. 기본 주소·인증키는 weather.source.* 설정값. */
@Configuration(proxyBeanMethods = false)
class WeatherSourceConfig {

	@Bean
	ExternalCalls weatherExternalCalls(WeatherProperties properties) {
		return new ExternalCalls(properties.external());
	}

	@Bean
	KmaSource kmaSource(WeatherSourceProperties source, WeatherProperties properties, ExternalCalls calls) {
		return new KmaHttpSource(source.kma(),
				HttpSourceSupport.client(source.kma().baseUrl(), properties.external().timeout()), calls);
	}

	@Bean
	AirKoreaSource airKoreaSource(WeatherSourceProperties source, WeatherProperties properties, ExternalCalls calls) {
		return new AirKoreaHttpSource(source.airKorea(),
				HttpSourceSupport.client(source.airKorea().baseUrl(), properties.external().timeout()), calls);
	}

}
