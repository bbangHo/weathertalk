package kr.weathertalk.shared.web;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 브라우저에서 API를 부르는 출처(Origin) 허용 목록. 네이티브 앱은 CORS와 무관하고, 앱인토스 웹 미니앱(WebView)만 해당한다.
 *
 * @param allowedOrigins 허용 출처. 비면 CORS 응답 헤더를 붙이지 않는다(다른 출처의 브라우저 호출 차단)
 */
@ConfigurationProperties("shared.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of()
				: allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).toList();
	}

}
