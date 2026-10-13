package kr.weathertalk.weather.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeatherProperties.class)
public class WeatherPropertiesConfig {
}
