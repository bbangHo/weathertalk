package kr.weathertalk.notification.internal.push;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;

import kr.weathertalk.notification.DevicePlatform;

/**
 * FCM HTTP v1 어댑터 (FR-NOTI-12): 서비스 계정 JWT → 접근 토큰 발급(캐시), 건별 발송과 응답 분류(성공·무효 토큰·일시 오류·메시지 오류),
 * 묶음 키. 실제 FCM 대신 WireMock을 쓴다. 자격이 없으면 발송 비활성(기동은 정상).
 */
class FcmPushSenderTests {

	static final String SEND = "/fcm/v1/projects/test-project/messages:send";

	WireMockServer server;
	RSAKey key;
	FcmPushSender sender;

	@BeforeEach
	void setUp() throws Exception {
		server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		key = new RSAKeyGenerator(2048).keyID("sa-key-1").generate();
		String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
					.encodeToString(key.toRSAPrivateKey().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
		String json = """
				{"type":"service_account","project_id":"test-project","private_key_id":"sa-key-1",
				 "private_key":"%s","client_email":"fcm@test-project.iam.gserviceaccount.com","token_uri":"%s/token"}
				""".formatted(pem.replace("\n", "\\n"), server.baseUrl());
		server.stubFor(post(urlPathEqualTo("/token"))
			.willReturn(okJson("{\"access_token\":\"ya29.test\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
		sender = new FcmPushSender(FcmServiceAccount.parse(json), server.baseUrl() + "/fcm", Duration.ofSeconds(2),
				List.of("UNREGISTERED", "SENDER_ID_MISMATCH", "INVALID_ARGUMENT"),
				Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));
	}

	@AfterEach
	void tearDown() {
		server.stop();
	}

	private static PushMessage message(String token) {
		return new PushMessage(token, DevicePlatform.ANDROID, "제목", "본문",
				Map.of("kind", "COMMENT", "postId", "p1", "target", "post.detail"), "COMMENT:p1");
	}

	private void fcmError(String token, int status, String fcmStatus, String errorCode, String message) {
		String details = errorCode == null ? "[]"
				: "[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"" + errorCode + "\"}]";
		server.stubFor(post(urlPathEqualTo(SEND)).withRequestBody(matchingJsonPath("$.message.token", equalTo(token)))
			.willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"error\":{\"code\":" + status + ",\"message\":\"" + message + "\",\"status\":\"" + fcmStatus
						+ "\",\"details\":" + details + "}}")));
	}

	@Test
	void sendsWithServiceAccountTokenAndClassifiesEachResponse() throws Exception {
		server.stubFor(post(urlPathEqualTo(SEND)).atPriority(10).willReturn(okJson("{\"name\":\"projects/test-project/messages/1\"}")));
		fcmError("gone", 404, "NOT_FOUND", "UNREGISTERED", "Requested entity was not found.");
		fcmError("bad-token", 400, "INVALID_ARGUMENT", "INVALID_ARGUMENT",
				"The registration token is not a valid FCM registration token");
		fcmError("bad-payload", 400, "INVALID_ARGUMENT", "INVALID_ARGUMENT", "Invalid value at 'message.data'");
		fcmError("busy", 503, "UNAVAILABLE", "UNAVAILABLE", "The service is currently unavailable.");
		fcmError("quota", 429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED", "Quota exceeded.");

		List<PushResult> results = sender.send(List.of(message("ok"), message("gone"), message("bad-token"),
				message("bad-payload"), message("busy"), message("quota")));

		assertThat(results).extracting(PushResult::outcome)
			.containsExactly(PushResult.Outcome.SUCCESS, PushResult.Outcome.INVALID_TOKEN,
					PushResult.Outcome.INVALID_TOKEN, PushResult.Outcome.MESSAGE_ERROR, PushResult.Outcome.TRANSIENT,
					PushResult.Outcome.TRANSIENT);
		assertThat(results.get(1).code()).isEqualTo("UNREGISTERED");

		// 접근 토큰은 한 번만 받아 재사용하고, 발송은 Bearer 토큰 + 묶음 키 + 데이터 필드를 싣는다
		server.verify(1, postRequestedFor(urlPathEqualTo("/token"))
			.withRequestBody(containing("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")));
		server.verify(postRequestedFor(urlPathEqualTo(SEND)).withHeader("Authorization", equalTo("Bearer ya29.test"))
			.withRequestBody(matchingJsonPath("$.message.android.collapse_key", equalTo("COMMENT:p1")))
			.withRequestBody(matchingJsonPath("$.message.apns.headers['apns-collapse-id']", equalTo("COMMENT:p1")))
			.withRequestBody(matchingJsonPath("$.message.data.target", equalTo("post.detail")))
			.withRequestBody(matchingJsonPath("$.message.notification.title", equalTo("제목"))));
		String assertion = server.findAll(postRequestedFor(urlPathEqualTo("/token")))
			.get(0)
			.getBodyAsString()
			.replaceAll(".*assertion=", "");
		SignedJWT jwt = SignedJWT.parse(java.net.URLDecoder.decode(assertion, StandardCharsets.UTF_8));
		assertThat(jwt.verify(new RSASSAVerifier(key.toPublicJWK()))).isTrue();
		assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
			.isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
		assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(server.baseUrl() + "/token");
	}

	@Test
	void networkFailureAndTokenEndpointOutageAreTransient() {
		server.stubFor(post(urlPathEqualTo(SEND)).willReturn(aResponse().withFixedDelay(3000).withStatus(200)));
		assertThat(sender.send(List.of(message("slow")))).extracting(PushResult::outcome)
			.containsExactly(PushResult.Outcome.TRANSIENT);

		server.resetAll();
		server.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(500)));
		FcmPushSender fresh = new FcmPushSender(
				FcmServiceAccount.parse(
						"{\"project_id\":\"p\",\"client_email\":\"e@x\",\"private_key\":\"" + pemOneLine() + "\",\"token_uri\":\""
								+ server.baseUrl() + "/token\"}"),
				server.baseUrl() + "/fcm", Duration.ofSeconds(2), List.of("UNREGISTERED"), Clock.systemUTC());
		assertThat(fresh.send(List.of(message("x")))).extracting(PushResult::code).containsExactly("AUTH_UNAVAILABLE");
	}

	/** 자격 파일이 없으면 발송 비활성으로 기동한다(경고 로그). */
	@Test
	void missingOrBrokenCredentialsDisableSending(@TempDir Path dir) throws Exception {
		var properties = new kr.weathertalk.notification.internal.config.NotificationPushProperties.Fcm(
				"https://fcm.googleapis.com", "", Duration.ofSeconds(5));
		var notification = kr.weathertalk.notification.internal.NotificationPropertiesFixture.defaults();
		assertThat(PushSenderConfig.create(properties, notification, Clock.systemUTC()).enabled()).isFalse();
		Path broken = Files.writeString(dir.resolve("broken.json"), "{not json");
		var brokenProperties = new kr.weathertalk.notification.internal.config.NotificationPushProperties.Fcm(
				"https://fcm.googleapis.com", broken.toString(), Duration.ofSeconds(5));
		assertThat(PushSenderConfig.create(brokenProperties, notification, Clock.systemUTC()).enabled()).isFalse();
	}

	private String pemOneLine() {
		try {
			return ("-----BEGIN PRIVATE KEY-----\n"
					+ Base64.getEncoder().encodeToString(key.toRSAPrivateKey().getEncoded())
					+ "\n-----END PRIVATE KEY-----\n").replace("\n", "\\n");
		}
		catch (com.nimbusds.jose.JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

}
