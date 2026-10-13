package kr.weathertalk.weather.internal.collect;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.internal.config.WeatherProperties;

/**
 * 발표 시각 선정 (BR-WEA-02): "현재 받을 수 있는 최신 발표 시각" = 발표 시각 + 제공 지연(*.availability-delay) ≤ 현재인
 * 발표 시각 중 가장 늦은 것. 00:00~02:XX(지연 포함)의 단기예보는 전날 23시가 된다(legacy 04-#9).
 */
@Component
public class IssueTimes {

	private final WeatherProperties properties;

	public IssueTimes(WeatherProperties properties) {
		this.properties = properties;
	}

	public Duration availabilityDelay(CollectionKind kind) {
		return switch (kind) {
			case OBSERVATION -> properties.observation().availabilityDelay();
			case SHORT_TERM -> properties.shortTerm().availabilityDelay();
			case MID_LAND, MID_TEMPERATURE -> properties.midTerm().availabilityDelay();
			case AIR -> properties.air().availabilityDelay();
			case UV -> properties.uv().availabilityDelay();
			case AIR_STATIONS -> Duration.ZERO;
		};
	}

	public Instant latest(CollectionKind kind, Instant now) {
		ZonedDateTime candidate = now.minus(availabilityDelay(kind)).atZone(TimeConfig.SEOUL).truncatedTo(ChronoUnit.HOURS);
		return onOrBefore(kind, candidate);
	}

	public Instant previous(CollectionKind kind, Instant issuedAt) {
		return onOrBefore(kind, issuedAt.atZone(TimeConfig.SEOUL).truncatedTo(ChronoUnit.HOURS).minusHours(1));
	}

	public Instant availableAt(CollectionKind kind, Instant issuedAt) {
		return issuedAt.plus(availabilityDelay(kind));
	}

	private static Instant onOrBefore(CollectionKind kind, ZonedDateTime candidate) {
		if (kind.issueHours().isEmpty()) {
			return candidate.toInstant();
		}
		ZonedDateTime t = candidate;
		while (!kind.issueHours().contains(t.getHour())) {
			t = t.minusHours(1);
		}
		return t.toInstant();
	}

}
