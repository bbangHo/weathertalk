package kr.weathertalk.weather.internal.source;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;

/**
 * 에어코리아 기능(오퍼레이션)별 일일 호출 카운터. 한도는 공공데이터포털이 기능별·날짜별로 세므로 같은 단위로 센다.
 * 재시도도 한도를 쓰므로 실제 HTTP 시도마다 센다. 한도를 넘겨도 막지 않고 경고만 남긴다 — 막으면 대기질이 조용히 끊긴다.
 * 인스턴스가 여럿이어도 합산되도록 Redis에 두고, Redis 장애면 세지 않는다(수집을 막지 않음).
 */
public class AirKoreaCallCounter {

	private static final Logger log = LoggerFactory.getLogger(AirKoreaCallCounter.class);

	static final String PREFIX = "weather:airkorea:calls:";

	private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

	/** 날짜가 바뀐 뒤 확인할 여유를 두고 지운다. */
	private static final Duration KEY_TTL = Duration.ofDays(2);

	public enum Level {

		NORMAL, NEAR_LIMIT, OVER_LIMIT, UNKNOWN

	}

	private final StringRedisTemplate redis;
	private final WeatherSourceProperties.AirKorea properties;
	private final Clock clock;

	public AirKoreaCallCounter(StringRedisTemplate redis, WeatherSourceProperties.AirKorea properties, Clock clock) {
		this.redis = redis;
		this.properties = properties;
		this.clock = clock;
	}

	/** 호출 1건을 센다. */
	public Level record(String operation) {
		String key = key(operation, today());
		long count;
		try {
			Long incremented = redis.opsForValue().increment(key);
			if (incremented == null) {
				return Level.UNKNOWN;
			}
			count = incremented;
			if (count == 1) {
				redis.expire(key, KEY_TTL);
			}
		}
		catch (RuntimeException e) {
			log.debug("에어코리아 호출 카운터 사용 불가: {}", e.getClass().getSimpleName());
			return Level.UNKNOWN;
		}
		int limit = properties.dailyLimit();
		long warnAt = warnThreshold();
		if (count > limit) {
			log.warn("에어코리아 {} 오늘 호출 {}건 — 하루 한도 {}건 초과(원천이 '요청 제한 횟수 초과'로 거부할 수 있음)", operation, count,
					limit);
			return Level.OVER_LIMIT;
		}
		if (count >= warnAt) {
			// 경고 문턱을 처음 넘을 때와 한도에 닿을 때만 남긴다(매 호출 로그 폭주 방지)
			if (count == warnAt || count == limit) {
				log.warn("에어코리아 {} 오늘 호출 {}건 — 하루 한도 {}건에 가까워짐", operation, count, limit);
			}
			return Level.NEAR_LIMIT;
		}
		return Level.NORMAL;
	}

	/** 오늘(Asia/Seoul) 센 호출 수. 모르면 -1. */
	public long today(String operation) {
		try {
			String value = redis.opsForValue().get(key(operation, today()));
			return value == null ? 0 : Long.parseLong(value);
		}
		catch (RuntimeException e) {
			return -1;
		}
	}

	long warnThreshold() {
		return Math.max(1, (long) Math.ceil(properties.dailyLimit() * properties.dailyLimitWarnRatio()));
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), TimeConfig.SEOUL);
	}

	static String key(String operation, LocalDate day) {
		return PREFIX + operation + ":" + DAY.format(day);
	}

}
