package kr.weathertalk.community.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommunityProperties.class)
public class CommunityPropertiesConfig {
}
