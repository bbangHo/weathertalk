package kr.weathertalk.member.internal.auth.provider;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jwt.JWTClaimsSet;

import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialVerifier;
import kr.weathertalk.member.internal.auth.ProviderUnlinker;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.config.MemberProviderProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 구글 로그인 검증·토큰 철회 (BR-MEM-03, FR-MEM-02 규칙 2, FR-MEM-09 규칙 3, 01 §9 미결 #3 — 서버 인가 코드 방식으로 구현).
 * <ul>
 * <li>검증: ID 토큰 서명(구글 JWKS) + iss(accounts.google.com) + aud(GOOGLE_CLIENT_IDS 중 하나) + 만료 → sub.</li>
 * <li>자격 보관: 앱이 함께 준 서버 인가 코드를 웹 클라이언트로 교환해 갱신 토큰(없으면 접근 토큰)을 받아 둔다. 실패해도 로그인은 계속한다.</li>
 * <li>해제: 보관한 토큰을 철회(/revoke)한다. 이미 무효면 성공으로 본다.</li>
 * </ul>
 */
@Component
class GoogleProvider implements ProviderCredentialVerifier, ProviderUnlinker {

	private static final Logger log = LoggerFactory.getLogger(GoogleProvider.class);

	private final MemberProviderProperties.Google google;
	private final ProviderHttp http;
	private final IdTokenVerifier idTokens;

	GoogleProvider(MemberProviderProperties properties, Clock clock) {
		this.google = properties.google();
		this.http = new ProviderHttp(properties.timeout());
		this.idTokens = new IdTokenVerifier(new JwksKeys(google.jwksUrl(), http, clock, properties.jwksCacheTtl(),
				properties.jwksRefreshMinInterval()), google.issuers(), clock);
	}

	@Override
	public AuthProvider provider() {
		return AuthProvider.GOOGLE;
	}

	@Override
	public boolean available() {
		return google.loginConfigured();
	}

	@Override
	public VerifiedProviderAccount verify(ProviderCredential credential) {
		if (!google.loginConfigured()) {
			throw ProviderHttp.notConfigured();
		}
		List<String> clientIds = google.clientIds().stream().filter(id -> id != null && !id.isBlank())
			.map(String::strip).toList();
		JWTClaimsSet claims = idTokens.verify(credential.token(), clientIds);
		return new VerifiedProviderAccount(AuthProvider.GOOGLE, claims.getSubject(), IdTokenVerifier.email(claims),
				exchange(credential.authorizationCode()));
	}

	private String exchange(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		if (!google.codeExchangeConfigured()) {
			log.warn("GOOGLE_SERVER_CLIENT_ID·GOOGLE_SERVER_CLIENT_SECRET가 없어 구글 연결 해제용 토큰을 받지 않습니다.");
			return null;
		}
		try {
			ProviderHttp.Response response = http.postForm(google.tokenUrl(), h -> {
			}, Map.of("code", code, "client_id", google.serverClientId().strip(), "client_secret",
					google.serverClientSecret().strip(), "grant_type", "authorization_code", "redirect_uri", ""));
			if (!response.ok()) {
				log.warn("구글 인가 코드 교환 실패(HTTP {}) — 연결 해제용 토큰 없이 로그인합니다.", response.status());
				return null;
			}
			String refresh = ProviderHttp.text(response.json(), "refresh_token");
			return refresh != null ? refresh : ProviderHttp.text(response.json(), "access_token");
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			log.warn("구글 인가 코드 교환 실패 — 연결 해제용 토큰 없이 로그인합니다: {}", e.getMessage());
			return null;
		}
	}

	@Override
	public boolean requiresCredential() {
		return true;
	}

	@Override
	public Result unlink(String providerUserId, String credential) {
		try {
			ProviderHttp.Response response = http.postForm(google.revokeUrl(), h -> {
			}, Map.of("token", credential));
			if (response.ok()) {
				return Result.succeeded("revoked");
			}
			String error = ProviderHttp.text(response.json(), "error");
			if ("invalid_token".equals(error)) {
				return Result.succeeded("already-revoked");
			}
			if (response.serverError()) {
				return Result.transientFailure("HTTP " + response.status());
			}
			return Result.permanentFailure("HTTP " + response.status() + " " + error);
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			return Result.transientFailure("network");
		}
	}

}
