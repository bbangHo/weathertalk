package kr.weathertalk.shared.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import kr.weathertalk.shared.idempotency.IdempotencyKey;

/**
 * 앱인토스 웹 미니앱(https://{appName}.apps.tossmini.com, QR 테스트는 private-apps)이 API를 부를 수 있게 CORS를 연다.
 * 인증은 Authorization 헤더 토큰이라 쿠키(자격 증명)는 허용하지 않는다(iOS WebView 서드파티 쿠키 차단과도 무관).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
class CorsConfig implements WebMvcConfigurer {

	/** 앱이 홈·알림 API에 보내는 기기 확인 헤더(notification 모듈 DeviceHeaders와 같은 이름) */
	private static final String DEVICE_ID = "X-Device-Id";

	private static final String DEVICE_KEY = "X-Device-Key";

	private final CorsProperties properties;

	CorsConfig(CorsProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		if (properties.allowedOrigins().isEmpty()) {
			return;
		}
		registry.addMapping("/api/**")
			.allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
			.allowedHeaders(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, IdempotencyKey.HEADER, DEVICE_ID, DEVICE_KEY)
			.allowCredentials(false)
			.maxAge(3600);
	}

}
