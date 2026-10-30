package kr.weathertalk.member.internal.auth.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialVerifier;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.config.MemberProviderProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;
import tools.jackson.databind.JsonNode;

/**
 * 앱인토스 사용자 식별키 로그인 검증 (사업자 없이 출시하는 토스 미니앱용).
 * <ul>
 * <li>앱이 {@code User.createAnonymousKeyAuthCode()}로 받은 인증 코드(5분·1회용)를 토큰으로 낸다.</li>
 * <li>서버가 mTLS로 교환 API를 불러 식별키(anonKey)를 받고, 그 값을 회원 식별값으로 쓴다. 앱이 보낸 식별키 값은 믿지 않는다.</li>
 * <li>교환 API는 업무 오류도 HTTP 200 + {@code resultType: FAIL}로 준다. 코드 무효(4011)는 인증 실패, 그 밖의 실패는 일시 오류로 본다.</li>
 * </ul>
 * 식별키는 본인 증명이 아니고 토스 쪽 연결 끊기 API도 없어 연결 해제({@code ProviderUnlinker})는 구현하지 않는다(탈퇴 때 우리 쪽 연결만 지운다).
 *
 * @see <a href="https://developers-apps-in-toss.toss.im/api/user-key.md">사용자 식별 키 API</a>
 */
@Component
class TossAnonProvider implements ProviderCredentialVerifier {

	static final String EXCHANGE = "/api-partner/v1/apps-in-toss/users/anon-key/exchange";

	/** 익명 사용자 인증 코드가 유효하지 않음(만료·재사용·위조) */
	static final String INVALID_CODE = "4011";

	private static final Logger log = LoggerFactory.getLogger(TossAnonProvider.class);

	private final MemberProviderProperties.TossAnon tossAnon;
	private final ProviderHttp http;

	TossAnonProvider(MemberProviderProperties properties) {
		this.tossAnon = properties.tossAnon();
		this.http = tossAnon.loginConfigured() ? mtlsHttp(properties) : null;
	}

	/** 인증서를 읽지 못하면 기동은 계속하고 이 로그인만 '일시적으로 사용할 수 없음'으로 둔다(다른 제공자와 같은 규칙). */
	private ProviderHttp mtlsHttp(MemberProviderProperties properties) {
		try {
			PemSslStoreDetails keyStore = PemSslStoreDetails
				.forCertificate(Files.readString(Path.of(tossAnon.certificatePath())))
				.withPrivateKey(Files.readString(Path.of(tossAnon.privateKeyPath())));
			SSLContext sslContext = SslBundle.of(new PemSslStoreBundle(keyStore, null)).createSslContext();
			return new ProviderHttp(properties.timeout(), sslContext);
		}
		catch (IOException | RuntimeException e) {
			log.error("앱인토스 mTLS 인증서를 읽지 못해 식별키 로그인을 끕니다: {}", e.toString());
			return null;
		}
	}

	@Override
	public AuthProvider provider() {
		return AuthProvider.TOSS_ANON;
	}

	@Override
	public boolean available() {
		return http != null;
	}

	@Override
	public VerifiedProviderAccount verify(ProviderCredential credential) {
		if (http == null) {
			throw ProviderHttp.notConfigured();
		}
		String code = credential.token();
		if (code == null || code.isBlank()) {
			throw ProviderHttp.rejected();
		}
		ProviderHttp.Response response;
		try {
			response = http.postJson(tossAnon.apiBaseUrl() + EXCHANGE, Map.of("code", code));
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			throw ProviderHttp.unavailable();
		}
		if (response.serverError()) {
			throw ProviderHttp.unavailable();
		}
		if (!response.ok()) {
			// 400: 요청 형식 오류(빈 코드 등) — 앱이 다시 코드를 받아 와야 한다
			throw ProviderHttp.rejected();
		}
		JsonNode json = response.json();
		String resultType = ProviderHttp.text(json, "resultType");
		if ("SUCCESS".equals(resultType)) {
			String anonKey = ProviderHttp.text(json.path("success"), "anonKey");
			if (anonKey == null) {
				log.warn("앱인토스 식별키 교환 응답에 anonKey가 없습니다");
				throw ProviderHttp.unavailable();
			}
			return new VerifiedProviderAccount(AuthProvider.TOSS_ANON, anonKey, null);
		}
		if ("FAIL".equals(resultType) && INVALID_CODE.equals(ProviderHttp.text(json.path("error"), "errorCode"))) {
			throw ProviderHttp.rejected();
		}
		// 요청 한도 초과(4095)·HTTP_TIMEOUT·INTERNAL_ERROR 등은 잠시 뒤 다시 시도하면 풀린다
		log.warn("앱인토스 식별키 교환 실패: resultType={}, error={}", resultType,
				KakaoProvider.abbreviate(json.path("error").toString()));
		throw ProviderHttp.unavailable();
	}

}
