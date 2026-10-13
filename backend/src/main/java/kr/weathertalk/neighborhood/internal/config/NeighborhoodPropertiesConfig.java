package kr.weathertalk.neighborhood.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NeighborhoodProperties.class)
public class NeighborhoodPropertiesConfig {
}
