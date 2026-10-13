package kr.weathertalk.weather.internal.source;

import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.MutableClock;
import kr.weathertalk.weather.internal.config.WeatherSourceProperties;
import kr.weathertalk.weather.internal.source.AirKoreaCallCounter.Level;

/**
 * 에어코리아 일일 호출 카운터: 기능별·날짜(Asia/Seoul)별로 Redis에 세고, 한도의 경고 비율에 닿으면 '한도에 가까움',
 * 넘기면 '초과'로 경고한다(호출은 막지 않는다).
 */
@IntegrationTest
class AirKoreaCallCounterTests {

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void reset() {
		clock.reset();
		Set<String> keys = redis.keys(AirKoreaCallCounter.PREFIX + "*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	void countsPerOperationAndDayAndWarnsNearAndOverLimit() {
		// 한도 5, 경고 비율 0.6 → 3번째부터 '가까움', 6번째부터 '초과'
		AirKoreaCallCounter counter = new AirKoreaCallCounter(redis,
				new WeatherSourceProperties.AirKorea("http://unused", "key", 1000, 5, 0.6), clock);
		clock.set(kst("2026-10-13T23:50"));

		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.NORMAL);
		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.NORMAL);
		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.NEAR_LIMIT);
		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.NEAR_LIMIT);
		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.NEAR_LIMIT);
		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.OVER_LIMIT);
		assertThat(counter.today("getCtprvnRltmMesureDnsty")).isEqualTo(6);
		// 기능별로 따로 센다(공공데이터포털 한도 단위)
		assertThat(counter.record("getMsrstnList")).isEqualTo(Level.NORMAL);
		assertThat(counter.today("getMsrstnList")).isEqualTo(1);

		// 날짜(Asia/Seoul)가 바뀌면 새로 센다. 키는 만료 시각을 가진다
		clock.set(kst("2026-10-14T00:05"));
		assertThat(counter.today("getCtprvnRltmMesureDnsty")).isZero();
		assertThat(counter.record("getCtprvnRltmMesureDnsty")).isEqualTo(Level.NORMAL);
		assertThat(redis.getExpire(AirKoreaCallCounter.PREFIX + "getCtprvnRltmMesureDnsty:20261014")).isPositive();
	}

}
