package kr.weathertalk.notification.internal.push;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * FCM HTTP v1 발송 어댑터 (BR-NOTI-11·12, FR-NOTI-12).
 * <p>
 * 서비스 계정 키로 OAuth 2.0 접근 토큰을 직접 받는다(서명된 JWT → 토큰 발급 주소, 만료 1분 전 갱신). Google 인증 라이브러리는
 * 이 한 가지 용도에 비해 의존성이 커서 들이지 않고, 이미 쓰는 JOSE 라이브러리로 서명한다.
 * HTTP v1에는 다건 발송 엔드포인트가 없어 배치 안의 건을 차례로 보내고 건별 응답으로 분류한다. 배치 간 동시성은 호출자가 정한다.
 * <ul>
 * <li>무효 토큰: notification.send.invalid-token-codes의 코드. INVALID_ARGUMENT는 오류 메시지가 등록 토큰을 가리킬 때만(그 밖은 메시지 오류).</li>
 * <li>일시 오류: UNAVAILABLE·INTERNAL·QUOTA_EXCEEDED, HTTP 5xx·429, 네트워크 실패·시간 초과, 접근 토큰 만료(401 — 토큰을 새로 받는다).</li>
 * <li>그 밖: 메시지 오류.</li>
 * </ul>
 */
public class FcmPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
	private static final Set<String> TRANSIENT_CODES = Set.of("UNAVAILABLE", "INTERNAL", "QUOTA_EXCEEDED");
	private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

	private final FcmServiceAccount account;
	private final String sendUrl;
	private final Set<String> invalidTokenCodes;
	private final Clock clock;
	private final RestClient client;

	private String accessToken;
	private Instant accessTokenExpiresAt;

	public FcmPushSender(FcmServiceAccount account, String baseUrl, Duration timeout, List<String> invalidTokenCodes,
			Clock clock) {
		this.account = account;
		this.sendUrl = baseUrl + "/v1/projects/" + account.projectId() + "/messages:send";
		this.invalidTokenCodes = invalidTokenCodes.stream().map(c -> c.strip().toUpperCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
		this.clock = clock;
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(timeout)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		this.client = RestClient.builder().requestFactory(factory).build();
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public List<PushResult> send(List<PushMessage> messages) {
		List<PushResult> results = new ArrayList<>(messages.size());
		for (PushMessage message : messages) {
			results.add(sendOne(message));
		}
		return results;
	}

	private PushResult sendOne(PushMessage message) {
		String token;
		try {
			token = accessToken();
		}
		catch (RuntimeException e) {
			log.warn("FCM 접근 토큰 발급 실패: {}", e.getMessage());
			return PushResult.transientFailure("AUTH_UNAVAILABLE");
		}
		try {
			Response response = client.post()
				.uri(sendUrl)
				.headers(h -> h.setBearerAuth(token))
				.contentType(MediaType.APPLICATION_JSON)
				.body(JSON.writeValueAsString(payload(message)))
				.exchange((request, res) -> read(res));
			if (response.status() >= 200 && response.status() < 300) {
				return PushResult.success();
			}
			if (response.status() == 401) {
				invalidateAccessToken();
				return PushResult.transientFailure("UNAUTHENTICATED");
			}
			return classify(response);
		}
		catch (ResourceAccessException e) {
			return PushResult.transientFailure("NETWORK");
		}
	}

	private PushResult classify(Response response) {
		JsonNode error = parse(response.body()).path("error");
		String status = text(error.path("status"));
		String errorCode = null;
		for (JsonNode detail : error.path("details")) {
			String code = text(detail.path("errorCode"));
			if (code != null) {
				errorCode = code;
				break;
			}
		}
		String code = errorCode != null ? errorCode : status != null ? status : "HTTP_" + response.status();
		String upper = code.toUpperCase(Locale.ROOT);
		if (invalidTokenCodes.contains(upper)) {
			if (!INVALID_ARGUMENT.equals(upper) || mentionsToken(text(error.path("message")))) {
				return PushResult.invalidToken(upper);
			}
			return PushResult.messageError(upper);
		}
		if (TRANSIENT_CODES.contains(upper) || response.status() >= 500 || response.status() == 429) {
			return PushResult.transientFailure(upper);
		}
		return PushResult.messageError(upper);
	}

	private static boolean mentionsToken(String message) {
		return message != null && message.toLowerCase(Locale.ROOT).contains("registration token");
	}

	private ObjectNode payload(PushMessage message) {
		ObjectNode root = JSON.createObjectNode();
		ObjectNode m = root.putObject("message");
		m.put("token", message.token());
		ObjectNode notification = m.putObject("notification");
		notification.put("title", message.title());
		notification.put("body", message.body());
		ObjectNode data = m.putObject("data");
		message.data().forEach(data::put);
		if (message.collapseKey() != null) {
			ObjectNode android = m.putObject("android");
			android.put("collapse_key", message.collapseKey());
			android.putObject("notification").put("tag", message.collapseKey());
			m.putObject("apns").putObject("headers").put("apns-collapse-id", message.collapseKey());
		}
		return root;
	}

	private synchronized String accessToken() {
		Instant now = clock.instant();
		if (accessToken != null && now.isBefore(accessTokenExpiresAt.minusSeconds(60))) {
			return accessToken;
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
		form.add("assertion", assertion(now));
		Response response;
		try {
			response = client.post()
				.uri(account.tokenUri())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.exchange((request, res) -> read(res));
		}
		catch (ResourceAccessException e) {
			throw new IllegalStateException("토큰 발급 주소에 연결하지 못함");
		}
		JsonNode json = parse(response.body());
		String token = text(json.path("access_token"));
		if (response.status() != 200 || token == null) {
			throw new IllegalStateException("토큰 발급 실패 HTTP " + response.status());
		}
		accessToken = token;
		accessTokenExpiresAt = now.plusSeconds(json.path("expires_in").asLong(3600));
		return accessToken;
	}

	private synchronized void invalidateAccessToken() {
		accessToken = null;
	}

	private String assertion(Instant now) {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(account.clientEmail())
			.subject(account.clientEmail())
			.audience(account.tokenUri())
			.claim("scope", SCOPE)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(3600)))
			.build();
		JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT);
		if (account.privateKeyId() != null) {
			header.keyID(account.privateKeyId());
		}
		SignedJWT jwt = new SignedJWT(header.build(), claims);
		try {
			jwt.sign(new RSASSASigner(account.privateKey()));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException("서비스 계정 서명 실패");
		}
	}

	private record Response(int status, String body) {
	}

	private static Response read(ClientHttpResponse response) throws IOException {
		return new Response(response.getStatusCode().value(),
				new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
	}

	private static JsonNode parse(String body) {
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

	private static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String text = node.asString();
		return text == null || text.isBlank() ? null : text;
	}
}
