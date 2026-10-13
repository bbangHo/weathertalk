package kr.weathertalk.shared.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모든 날짜·시각 판정은 이 Clock을 주입받아 Asia/Seoul 기준으로 한다 (contracts.md §4).
 * 테스트에서는 고정 Clock으로 교체한다.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Bean
	Clock clock() {
		return Clock.system(SEOUL);
	}

}
