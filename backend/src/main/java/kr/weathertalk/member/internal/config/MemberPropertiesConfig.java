package kr.weathertalk.member.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ MemberProperties.class, MemberProviderProperties.class })
public class MemberPropertiesConfig {
}
