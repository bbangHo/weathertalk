package kr.weathertalk.member.internal.auth.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 제공자 HTTP 호출 공통: 제한시간, 상태 코드·본문을 예외 없이 받기, JSON 해석.
 * 네트워크 실패·시간 초과는 {@link ProviderUnavailableException}으로 바꾼다(일시 오류).
 */
final class ProviderHttp {

	static final JsonMapper JSON = JsonMapper.builder().build();

	record Response(int status, String body) {

		boolean ok() {
			return status >= 200 && status < 300;
		}

		boolean serverError() {
			return status >= 500 || status == 429;
		}

		JsonNode json() {
			if (body == null || body.isBlank()) {
				return JSON.missingNode();
			}
			try {
				return JSON.readTree(body);
			}
			catch (JacksonException e) {
				return JSON.missingNode();
			}
		}

	}

	/** 제공자 장애(네트워크·5xx·제한). 로그인에서는 '일시 오류', 연결 해제에서는 재시도 대상이다. */
	static final class ProviderUnavailableException extends RuntimeException {

		ProviderUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private final RestClient client;

	ProviderHttp(Duration timeout) {
		this(timeout, null);
	}

	/** @param sslContext 클라이언트 인증서(mTLS)를 실을 SSL 설정. null이면 JDK 기본 */
	ProviderHttp(Duration timeout, SSLContext sslContext) {
		HttpClient.Builder builder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(timeout);
		if (sslContext != null) {
			builder.sslContext(sslContext);
		}
		HttpClient httpClient = builder.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		this.client = RestClient.builder().requestFactory(factory).build();
	}

	Response get(String url, Consumer<HttpHeaders> headers) {
		try {
			return client.get().uri(url).headers(headers).exchange((request, response) -> read(response));
		}
		catch (ResourceAccessException e) {
			throw new ProviderUnavailableException("제공자 호출 실패: " + url, e);
		}
	}

	Response postForm(String url, Consumer<HttpHeaders> headers, Map<String, String> form) {
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		form.forEach(body::add);
		try {
			return client.post()
				.uri(url)
				.headers(headers)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(body)
				.exchange((request, response) -> read(response));
		}
		catch (ResourceAccessException e) {
			throw new ProviderUnavailableException("제공자 호출 실패: " + url, e);
		}
	}

	Response postJson(String url, Object body) {
		try {
			return client.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.body(JSON.writeValueAsString(body))
				.exchange((request, response) -> read(response));
		}
		catch (ResourceAccessException e) {
			throw new ProviderUnavailableException("제공자 호출 실패: " + url, e);
		}
	}

	private static Response read(org.springframework.http.client.ClientHttpResponse response) throws IOException {
		byte[] bytes = response.getBody().readAllBytes();
		return new Response(response.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
	}

	static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return text == null || text.isBlank() ? null : text;
	}

	/** 자격 검증 실패(만료·위조·대상 불일치·제공자가 거절) — 모두 같은 인증 실패로 응답한다(legacy 01-#12). */
	static DomainException rejected() {
		return new DomainException(ErrorCode.AUTHENTICATION_FAILED, "로그인 제공자 자격을 확인하지 못했습니다. 다시 로그인해 주세요.");
	}

	static DomainException unavailable() {
		return new DomainException(ErrorCode.TEMPORARILY_UNAVAILABLE, "로그인 제공자에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
	}

	static DomainException notConfigured() {
		return new DomainException(ErrorCode.TEMPORARILY_UNAVAILABLE, "지금은 이 로그인 수단을 쓸 수 없습니다.");
	}

}
