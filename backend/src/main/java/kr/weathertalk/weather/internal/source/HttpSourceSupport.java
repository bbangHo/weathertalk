package kr.weathertalk.weather.internal.source;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP 어댑터 공통: 제한시간, 쿼리 인코딩(인증키의 '+', '/' 보존), JSON 해석. */
final class HttpSourceSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private HttpSourceSupport() {
	}

	static RestClient client(String baseUrl, Duration timeout) {
		// HTTP/1.1 고정: 평문 주소에서 h2c 업그레이드 협상 왕복이 호출 제한시간을 잡아먹지 않게
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(timeout)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	/** 쿼리 값은 URI 변수로 넘겨 값 단위로 인코딩한다(인증키의 '+'가 공백으로 바뀌지 않게). */
	static String get(RestClient client, String path, Map<String, Object> params) {
		Map<String, Object> values = new LinkedHashMap<>(params);
		return client.get().uri(builder -> {
			builder.path(path);
			values.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
			return builder.build(values);
		}).retrieve().body(String.class);
	}

	static JsonNode parse(String source, String body) {
		if (body == null || body.isBlank()) {
			throw new SourceException.Failure(source + " 빈 응답");
		}
		try {
			return JSON.readTree(body);
		}
		catch (JacksonException e) {
			// 공공데이터포털은 인증 오류 등을 XML로 돌려준다 → 형식 오류로 같은 실패 처리(BR-WEA-06)
			throw new SourceException.Failure(source + " 응답 형식 오류: " + abbreviate(body), e);
		}
	}

	static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String value = node.asString();
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static String abbreviate(String body) {
		String oneLine = body.replaceAll("\\s+", " ");
		return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
	}

}
