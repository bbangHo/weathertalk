package kr.weathertalk.member.internal.auth.provider;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * 제공자 공개키 목록(JWKS) 캐시. 수명(jwks-cache-ttl) 안에서는 다시 받지 않고, 모르는 kid가 오면 키 회전으로 보고
 * 최소 간격(jwks-refresh-min-interval)을 지켜 다시 받는다. 받기에 실패하면 남은 캐시로 검증을 계속한다(제공자 순간 장애 내성).
 */
final class JwksKeys {

	private static final Logger log = LoggerFactory.getLogger(JwksKeys.class);

	private final String url;
	private final ProviderHttp http;
	private final Clock clock;
	private final Duration ttl;
	private final Duration refreshMinInterval;

	private JWKSet keys;
	private Instant fetchedAt;
	private Instant lastAttemptAt;

	JwksKeys(String url, ProviderHttp http, Clock clock, Duration ttl, Duration refreshMinInterval) {
		this.url = url;
		this.http = http;
		this.clock = clock;
		this.ttl = ttl;
		this.refreshMinInterval = refreshMinInterval;
	}

	/** @return kid의 RSA 공개키. 다시 받아도 없으면 빈 값(위조·폐기 키) */
	synchronized Optional<RSAKey> find(String kid) {
		Instant now = clock.instant();
		if (keys == null || !now.isBefore(fetchedAt.plus(ttl))) {
			refresh(now);
		}
		Optional<RSAKey> found = lookup(kid);
		if (found.isEmpty() && (lastAttemptAt == null || !now.isBefore(lastAttemptAt.plus(refreshMinInterval)))) {
			refresh(now);
			found = lookup(kid);
		}
		return found;
	}

	private Optional<RSAKey> lookup(String kid) {
		if (keys == null || kid == null) {
			return Optional.empty();
		}
		JWK jwk = keys.getKeyByKeyId(kid);
		return jwk instanceof RSAKey rsa ? Optional.of(rsa) : Optional.empty();
	}

	private void refresh(Instant now) {
		lastAttemptAt = now;
		try {
			ProviderHttp.Response response = http.get(url, headers -> {
			});
			if (!response.ok()) {
				throw new ProviderHttp.ProviderUnavailableException("JWKS 응답 " + response.status(), null);
			}
			keys = JWKSet.parse(response.body());
			fetchedAt = now;
		}
		catch (ParseException | ProviderHttp.ProviderUnavailableException e) {
			if (keys == null) {
				throw new ProviderHttp.ProviderUnavailableException("공개키 목록을 받지 못했습니다: " + url, e);
			}
			log.warn("공개키 목록 갱신 실패 — 남은 캐시로 검증합니다: {} ({})", url, e.getMessage());
		}
	}

}
