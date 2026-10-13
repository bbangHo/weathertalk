package kr.weathertalk.outfit.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutfitProperties.class)
public class OutfitPropertiesConfig {
}
