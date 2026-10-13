package kr.weathertalk.feeling.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeelingProperties.class)
public class FeelingPropertiesConfig {
}
