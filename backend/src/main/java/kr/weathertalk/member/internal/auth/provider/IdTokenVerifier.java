package kr.weathertalk.member.internal.auth.provider;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 애플 identity token·구글 ID 토큰 공통 검증 (BR-MEM-03, legacy 01-#13): RS256 서명(제공자 JWKS) + iss + aud + 만료 + sub.
 * 만료는 주입받은 Clock으로 판정한다. 어느 하나라도 어긋나면 같은 인증 실패로 끝난다(legacy 01-#12).
 */
final class IdTokenVerifier {

	private final JwksKeys keys;
	private final Collection<String> issuers;
	private final Clock clock;

	IdTokenVerifier(JwksKeys keys, Collection<String> issuers, Clock clock) {
		this.keys = keys;
		this.issuers = List.copyOf(issuers);
		this.clock = clock;
	}

	/**
	 * @param audiences 허용 대상(우리 클라이언트 ID 목록)
	 * @return 검증된 클레임
	 */
	JWTClaimsSet verify(String token, Collection<String> audiences) {
		if (token == null || token.isBlank()) {
			throw ProviderHttp.rejected();
		}
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
				throw ProviderHttp.rejected();
			}
			Optional<RSAKey> key = keys.find(jwt.getHeader().getKeyID());
			if (key.isEmpty() || !jwt.verify(new RSASSAVerifier(key.get()))) {
				throw ProviderHttp.rejected();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Instant now = clock.instant();
			boolean issuerOk = claims.getIssuer() != null && issuers.contains(claims.getIssuer());
			boolean audienceOk = claims.getAudience() != null
					&& claims.getAudience().stream().anyMatch(audiences::contains);
			boolean notExpired = claims.getExpirationTime() != null
					&& now.isBefore(claims.getExpirationTime().toInstant());
			boolean hasSubject = claims.getSubject() != null && !claims.getSubject().isBlank();
			if (!issuerOk || !audienceOk || !notExpired || !hasSubject) {
				throw ProviderHttp.rejected();
			}
			return claims;
		}
		catch (ParseException | JOSEException e) {
			throw ProviderHttp.rejected();
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			throw ProviderHttp.unavailable();
		}
	}

	/** 검증된 토큰에서 우리 클라이언트 ID 중 하나인 aud(애플 토큰 교환·철회의 client_id). */
	static String matchedAudience(JWTClaimsSet claims, Collection<String> audiences) {
		return claims.getAudience().stream().filter(audiences::contains).findFirst().orElseThrow();
	}

	/** 이메일 클레임(선택 정보). 없으면 null — "null" 대체 문자열을 만들지 않는다. */
	static String email(JWTClaimsSet claims) {
		Object email = claims.getClaim("email");
		return email instanceof String s && !s.isBlank() ? s : null;
	}

}
