package kr.weathertalk.weather.internal.collect;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import kr.weathertalk.weather.Freshness;
import kr.weathertalk.weather.internal.config.WeatherProperties;

/**
 * 신선도 3단계 판정 (BR-WEA-03~05). 오직 발표·관측·측정 시각과 판정 시각으로 정한다 — 조회·적중이 유효 기간을 늘리지 않는다(legacy 04-#8).
 */
@Component
public class FreshnessRules {

	private final IssueTimes issueTimes;
	private final WeatherProperties properties;

	public FreshnessRules(IssueTimes issueTimes, WeatherProperties properties) {
		this.issueTimes = issueTimes;
		this.properties = properties;
	}

	/** BR-WEA-04 실황. */
	public Freshness observation(Instant observedAt, Instant now) {
		return byStaleness(CollectionKind.OBSERVATION, observedAt, now, properties.observation().maxStaleness());
	}

	/** BR-WEA-05 대기질. */
	public Freshness air(Instant measuredAt, Instant now) {
		return byStaleness(CollectionKind.AIR, measuredAt, now, properties.air().maxStaleness());
	}

	/**
	 * BR-WEA-03 단기예보.
	 *
	 * @param hasRemainingSlots 현재 시 이후 칸이 남아 있는가
	 */
	public Freshness shortTerm(Instant issuedAt, Instant now, boolean hasRemainingSlots) {
		if (issuedAt == null || !hasRemainingSlots) {
			return Freshness.NONE;
		}
		if (!issuedAt.isBefore(issueTimes.latest(CollectionKind.SHORT_TERM, now))) {
			return Freshness.FRESH;
		}
		return !issuedAt.plus(properties.shortTerm().maxStaleness()).isBefore(now) ? Freshness.DELAYED : Freshness.NONE;
	}

	/** BR-WEA-05 자외선·중기: 최신 발표분 정상, 직전 발표분 지연, 그보다 오래되면 없음. */
	public Freshness byIssue(CollectionKind kind, Instant issuedAt, Instant now) {
		if (issuedAt == null) {
			return Freshness.NONE;
		}
		Instant latest = issueTimes.latest(kind, now);
		if (!issuedAt.isBefore(latest)) {
			return Freshness.FRESH;
		}
		return !issuedAt.isBefore(issueTimes.previous(kind, latest)) ? Freshness.DELAYED : Freshness.NONE;
	}

	private Freshness byStaleness(CollectionKind kind, Instant at, Instant now, Duration maxStaleness) {
		if (at == null) {
			return Freshness.NONE;
		}
		if (!at.isBefore(issueTimes.latest(kind, now))) {
			return Freshness.FRESH;
		}
		return !at.plus(maxStaleness).isBefore(now) ? Freshness.DELAYED : Freshness.NONE;
	}

}
