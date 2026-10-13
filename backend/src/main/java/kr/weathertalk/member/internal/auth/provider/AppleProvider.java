package kr.weathertalk.member.internal.auth.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialVerifier;
import kr.weathertalk.member.internal.auth.ProviderUnlinker;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.config.MemberProviderProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 애플 로그인 검증·토큰 철회 (BR-MEM-03, FR-MEM-02 규칙 2, FR-MEM-09 규칙 3).
 * <ul>
 * <li>검증: identity token 서명(애플 JWKS) + iss(https://appleid.apple.com) + aud(APPLE_CLIENT_ID 목록) + 만료 → sub. 이메일은 선택.</li>
 * <li>자격 보관: 인가 코드를 /auth/token으로 교환해 갱신 토큰을 받아 둔다(탈퇴 때 재로그인 없이 철회, legacy 01-#4).
 * 교환 실패는 로그인을 막지 않는다.</li>
 * <li>해제: 보관한 갱신 토큰을 /auth/revoke로 철회한다. 클라이언트 시크릿은 .p8 키로 서명한 ES256 JWT다.</li>
 * </ul>
 */
@Component
class AppleProvider implements ProviderCredentialVerifier, ProviderUnlinker {

	private static final Logger log = LoggerFactory.getLogger(AppleProvider.class);

	static final String KEYS = "/auth/keys";
	static final String TOKEN = "/auth/token";
	static final String REVOKE = "/auth/revoke";

	private final MemberProviderProperties.Apple apple;
	private final ProviderHttp http;
	private final IdTokenVerifier idTokens;
	private final Clock clock;

	AppleProvider(MemberProviderProperties properties, Clock clock) {
		this.apple = properties.apple();
		this.http = new ProviderHttp(properties.timeout());
		this.clock = clock;
		this.idTokens = new IdTokenVerifier(new JwksKeys(apple.baseUrl() + KEYS, http, clock,
				properties.jwksCacheTtl(), properties.jwksRefreshMinInterval()), List.of(apple.issuer()), clock);
	}

	@Override
	public AuthProvider provider() {
		return AuthProvider.APPLE;
	}

	@Override
	public boolean available() {
		return apple.loginConfigured();
	}

	@Override
	public VerifiedProviderAccount verify(ProviderCredential credential) {
		if (!apple.loginConfigured()) {
			throw ProviderHttp.notConfigured();
		}
		List<String> clientIds = clientIds();
		JWTClaimsSet claims = idTokens.verify(credential.token(), clientIds);
		String clientId = IdTokenVerifier.matchedAudience(claims, clientIds);
		return new VerifiedProviderAccount(AuthProvider.APPLE, claims.getSubject(), IdTokenVerifier.email(claims),
				exchange(clientId, credential.authorizationCode()));
	}

	/** @return "client_id 갱신토큰" 또는 null */
	private String exchange(String clientId, String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		if (!apple.clientSecretConfigured()) {
			log.warn("APPLE_TEAM_ID·APPLE_KEY_ID·APPLE_PRIVATE_KEY_PATH가 없어 애플 연결 해제용 토큰을 받지 않습니다.");
			return null;
		}
		try {
			ProviderHttp.Response response = http.postForm(apple.baseUrl() + TOKEN, h -> {
			}, Map.of("client_id", clientId, "client_secret", clientSecret(clientId), "code", code, "grant_type",
					"authorization_code"));
			String refreshToken = response.ok() ? ProviderHttp.text(response.json(), "refresh_token") : null;
			if (refreshToken == null) {
				log.warn("애플 인가 코드 교환 실패(HTTP {}) — 연결 해제용 토큰 없이 로그인합니다.", response.status());
				return null;
			}
			return clientId + " " + refreshToken;
		}
		catch (ProviderHttp.ProviderUnavailableException | IllegalStateException e) {
			log.warn("애플 인가 코드 교환 실패 — 연결 해제용 토큰 없이 로그인합니다: {}", e.getMessage());
			return null;
		}
	}

	@Override
	public boolean requiresCredential() {
		return true;
	}

	@Override
	public Result unlink(String providerUserId, String credential) {
		int space = credential == null ? -1 : credential.indexOf(' ');
		if (space <= 0) {
			return Result.permanentFailure("자격 형식 오류");
		}
		if (!apple.clientSecretConfigured()) {
			return Result.transientFailure("애플 클라이언트 시크릿 키 미설정");
		}
		String clientId = credential.substring(0, space);
		String refreshToken = credential.substring(space + 1);
		try {
			ProviderHttp.Response response = http.postForm(apple.baseUrl() + REVOKE, h -> {
			}, Map.of("client_id", clientId, "client_secret", clientSecret(clientId), "token", refreshToken,
					"token_type_hint", "refresh_token"));
			if (response.ok()) {
				return Result.succeeded("revoked");
			}
			String error = ProviderHttp.text(response.json(), "error");
			if ("invalid_grant".equals(error)) {
				return Result.succeeded("already-revoked");
			}
			if (response.serverError() || "invalid_client".equals(error)) {
				return Result.transientFailure("HTTP " + response.status() + " " + error);
			}
			return Result.permanentFailure("HTTP " + response.status() + " " + error);
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			return Result.transientFailure("network");
		}
		catch (IllegalStateException e) {
			return Result.transientFailure(e.getMessage());
		}
	}

	/** 클라이언트 시크릿(ES256 JWT): iss=팀 ID, sub=client_id, aud=애플, kid=키 ID. 호출마다 짧은 수명으로 새로 만든다. */
	String clientSecret(String clientId) {
		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(apple.teamId().strip())
			.subject(clientId)
			.audience(apple.issuer())
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(apple.clientSecretTtl())))
			.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.keyId().strip()).type(JOSEObjectType.JWT).build(),
				claims);
		try {
			jwt.sign(new ECDSASigner(privateKey()));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException("애플 클라이언트 시크릿 서명 실패", e);
		}
	}

	private ECPrivateKey privateKey() {
		try {
			String pem = Files.readString(Path.of(apple.privateKeyPath().strip()), StandardCharsets.UTF_8);
			String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
			return (ECPrivateKey) KeyFactory.getInstance("EC")
				.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
		}
		catch (IOException | GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
			throw new IllegalStateException("애플 개인 키(.p8)를 읽지 못했습니다: " + e.getClass().getSimpleName());
		}
	}

	private List<String> clientIds() {
		return apple.clientIds().stream().filter(id -> id != null && !id.isBlank()).map(String::strip).toList();
	}

}
