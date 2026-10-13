package kr.weathertalk.member.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 소셜 로그인 테스트용 키·설정값. 실제 제공자 키가 아니라 테스트에서 만든 RSA·EC 키다(외부 호출 없음).
 * 통합 테스트 설정(IntegrationTestConfiguration)이 이 값으로 member.providers.*를 WireMock 주소에 연결한다.
 */
public final class ProviderTestKeys {

	public static final String KAKAO_APP_ID = "123456";
	public static final String KAKAO_ADMIN_KEY = "test-kakao-admin-key";
	public static final String APPLE_ISSUER = "https://appleid.apple.com";
	public static final String APPLE_CLIENT_ID = "kr.weathertalk.app";
	public static final String APPLE_TEAM_ID = "TEAM123456";
	public static final String APPLE_KEY_ID = "KEY1234567";
	public static final String GOOGLE_ISSUER = "https://accounts.google.com";
	public static final List<String> GOOGLE_CLIENT_IDS = List.of("test-google-ios.apps.googleusercontent.com",
			"test-google-android.apps.googleusercontent.com");
	public static final String GOOGLE_SERVER_CLIENT_ID = "test-google-web.apps.googleusercontent.com";
	public static final String GOOGLE_SERVER_CLIENT_SECRET = "test-google-secret";

	public static final RSAKey APPLE_KEY = rsa("apple-k1");
	public static final RSAKey APPLE_ROTATED_KEY = rsa("apple-k2");
	public static final RSAKey GOOGLE_KEY = rsa("google-k1");
	/** 공격자 키: 애플 키와 kid가 같지만 다른 키(위조 서명) */
	public static final RSAKey FORGED_APPLE_KEY = rsa("apple-k1");

	/** 애플 클라이언트 시크릿 서명용 .p8 (P-256 PKCS#8 PEM). */
	public static final ECKey APPLE_CLIENT_SECRET_KEY = ec();
	public static final Path APPLE_PRIVATE_KEY_PATH = writeP8(APPLE_CLIENT_SECRET_KEY);

	private ProviderTestKeys() {
	}

	public static String jwks(RSAKey... keys) {
		return new JWKSet(List.of(keys).stream().map(RSAKey::toPublicJWK).map(k -> (com.nimbusds.jose.jwk.JWK) k).toList())
			.toString();
	}

	public static String idToken(RSAKey key, String issuer, String audience, String subject, Instant expiresAt,
			Map<String, Object> extra) {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer)
			.audience(audience)
			.subject(subject)
			.issueTime(Date.from(expiresAt.minusSeconds(600)))
			.expirationTime(Date.from(expiresAt));
		extra.forEach(claims::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims.build());
		try {
			jwt.sign(new RSASSASigner(key));
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
		return jwt.serialize();
	}

	private static RSAKey rsa(String kid) {
		try {
			return new RSAKeyGenerator(2048).keyID(kid).generate();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ECKey ec() {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID(APPLE_KEY_ID).generate();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Path writeP8(ECKey key) {
		try {
			byte[] pkcs8 = key.toECPrivateKey().getEncoded();
			String pem = "-----BEGIN PRIVATE KEY-----\n"
					+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs8)
					+ "\n-----END PRIVATE KEY-----\n";
			Path file = Files.createTempFile("AuthKey_test", ".p8");
			Files.writeString(file, pem);
			file.toFile().deleteOnExit();
			return file;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

}
