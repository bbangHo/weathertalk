package kr.weathertalk.member.internal.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import kr.weathertalk.member.internal.config.MemberProperties;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 접근·갱신 토큰 발급과 검증 (01-member.md §3.3, BR-MEM-04).
 * <p>
 * 두 종류는 ① 서로 다른 서명 키(설정 비밀값에서 용도별로 파생) ② 대상(aud) ③ 종류(typ) 클레임으로 구조적으로 구분한다.
 * 한쪽을 다른 쪽 자리에 내밀면 서명 검증부터 실패한다(legacy 01-#1). 토큰에는 회원 ID·세션 ID(·세대)만 싣고 이메일·제공자는 싣지 않는다.
 * 만료 판정은 주입받은 {@link Clock}으로 한다(테스트에서 시각을 옮길 수 있도록).
 */
@Component
public class TokenService {

	private static final String ISSUER = "weathertalk";
	private static final String CLAIM_TYPE = "typ";
	private static final String CLAIM_SESSION = "sid";
	private static final String CLAIM_GENERATION = "gen";

	enum Kind {

		ACCESS("access"), REFRESH("refresh");

		final String claim;

		Kind(String claim) {
			this.claim = claim;
		}

		String audience() {
			return ISSUER + ":" + claim;
		}

	}

	public record AccessClaims(UUID memberId, UUID sessionId) {
	}

	public record RefreshClaims(UUID memberId, UUID sessionId, int generation) {
	}

	private final Clock clock;
	private final MemberProperties properties;
	private final byte[] accessKey;
	private final byte[] refreshKey;

	TokenService(Clock clock, MemberProperties properties) {
		this.clock = clock;
		this.properties = properties;
		byte[] secret = properties.token().secret().getBytes(StandardCharsets.UTF_8);
		this.accessKey = derive(secret, Kind.ACCESS);
		this.refreshKey = derive(secret, Kind.REFRESH);
	}

	public IssuedTokens issue(UUID memberId, UUID sessionId, int generation) {
		Instant now = clock.instant();
		Instant accessExpiresAt = now.plus(properties.accessTokenTtl());
		Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
		JWTClaimsSet.Builder access = base(Kind.ACCESS, memberId, sessionId, now, accessExpiresAt);
		JWTClaimsSet.Builder refresh = base(Kind.REFRESH, memberId, sessionId, now, refreshExpiresAt)
			.claim(CLAIM_GENERATION, generation);
		return new IssuedTokens(sign(access.build(), accessKey), accessExpiresAt, sign(refresh.build(), refreshKey),
				refreshExpiresAt);
	}

	public AccessClaims verifyAccess(String token) {
		JWTClaimsSet claims = verify(token, Kind.ACCESS, true);
		return new AccessClaims(uuid(claims.getSubject()), uuid(stringClaim(claims, CLAIM_SESSION)));
	}

	public RefreshClaims verifyRefresh(String token) {
		return refreshClaims(verify(token, Kind.REFRESH, true));
	}

	/** 로그아웃용: 서명·종류는 확인하되 만료는 따지지 않는다(만료된 토큰으로도 세션을 끝낼 수 있게). */
	public RefreshClaims verifyRefreshIgnoringExpiry(String token) {
		return refreshClaims(verify(token, Kind.REFRESH, false));
	}

	/** 로그아웃용: 서명·종류는 확인하되 만료는 따지지 않는다. */
	public AccessClaims verifyAccessIgnoringExpiry(String token) {
		JWTClaimsSet claims = verify(token, Kind.ACCESS, false);
		return new AccessClaims(uuid(claims.getSubject()), uuid(stringClaim(claims, CLAIM_SESSION)));
	}

	private RefreshClaims refreshClaims(JWTClaimsSet claims) {
		Object generation = claims.getClaim(CLAIM_GENERATION);
		if (!(generation instanceof Number number)) {
			throw invalid();
		}
		return new RefreshClaims(uuid(claims.getSubject()), uuid(stringClaim(claims, CLAIM_SESSION)),
				number.intValue());
	}

	private JWTClaimsSet.Builder base(Kind kind, UUID memberId, UUID sessionId, Instant now, Instant expiresAt) {
		return new JWTClaimsSet.Builder().issuer(ISSUER)
			.audience(kind.audience())
			.subject(memberId.toString())
			.claim(CLAIM_SESSION, sessionId.toString())
			.claim(CLAIM_TYPE, kind.claim)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(expiresAt))
			.jwtID(UUID.randomUUID().toString());
	}

	private JWTClaimsSet verify(String token, Kind kind, boolean checkExpiry) {
		if (token == null || token.isBlank()) {
			throw invalid();
		}
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
					|| !jwt.verify(new MACVerifier(kind == Kind.ACCESS ? accessKey : refreshKey))) {
				throw invalid();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			List<String> audience = claims.getAudience();
			if (!ISSUER.equals(claims.getIssuer()) || audience == null || !audience.contains(kind.audience())
					|| !kind.claim.equals(claims.getClaim(CLAIM_TYPE)) || claims.getExpirationTime() == null) {
				throw invalid();
			}
			if (checkExpiry && !clock.instant().isBefore(claims.getExpirationTime().toInstant())) {
				throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "토큰이 만료되었습니다.");
			}
			return claims;
		}
		catch (ParseException | JOSEException e) {
			throw invalid();
		}
	}

	private static String stringClaim(JWTClaimsSet claims, String name) {
		Object value = claims.getClaim(name);
		if (!(value instanceof String s)) {
			throw invalid();
		}
		return s;
	}

	private static UUID uuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw invalid();
		}
	}

	private static String sign(JWTClaimsSet claims, byte[] key) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(key));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException("토큰 서명에 실패했습니다.", e);
		}
	}

	/** 용도별 서명 키 = HMAC-SHA256(설정 비밀값, 용도). 비밀값 하나로 종류마다 다른 키를 쓴다. */
	private static byte[] derive(byte[] secret, Kind kind) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal((ISSUER + "/" + kind.claim + "-token").getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static DomainException invalid() {
		return new DomainException(ErrorCode.AUTHENTICATION_FAILED, "유효하지 않은 토큰입니다.");
	}

}
