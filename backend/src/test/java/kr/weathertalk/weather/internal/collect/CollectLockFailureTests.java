package kr.weathertalk.weather.internal.collect;

import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.WeatherPropertiesFixture;
import kr.weathertalk.weather.internal.collect.CollectStateRepository.Result;

/**
 * legacy 06-#4: 수집 점유 저장소(Redis) 장애를 사용자 오류로 전파하지 않고 원천 수집으로 대체한다(장애 주입).
 */
class CollectLockFailureTests {

	@Test
	void collectsFromSourceWithoutLockWhenLockStoreIsDown_06_4() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis 연결 실패(장애 주입)"));
		when(redis.hasKey(any())).thenThrow(new RedisConnectionFailureException("Redis 연결 실패(장애 주입)"));
		CollectStateRepository states = mock(CollectStateRepository.class);
		when(states.find(any(), any())).thenReturn(Optional.empty());
		Instant now = kst("2026-10-13T09:15");
		CollectionCoordinator coordinator = new CollectionCoordinator(states, new CollectLock(redis),
				new IssueTimes(WeatherPropertiesFixture.defaults()), WeatherPropertiesFixture.defaults(),
				Clock.fixed(now, TimeConfig.SEOUL));
		AtomicReference<Instant> stored = new AtomicReference<>();
		List<Instant> collected = new ArrayList<>();

		CollectionCoordinator.Outcome outcome = coordinator.ensure(CollectionKind.OBSERVATION, "98:76",
				() -> Optional.ofNullable(stored.get()), issuedAt -> {
					collected.add(issuedAt);
					stored.set(issuedAt);
				}, false);

		assertThat(outcome).isEqualTo(CollectionCoordinator.Outcome.COLLECTED);
		assertThat(collected).containsExactly(kst("2026-10-13T09:00"));
		verify(states).record(CollectionKind.OBSERVATION, "98:76", kst("2026-10-13T09:00"), kst("2026-10-13T09:00"),
				Result.SUCCESS, now);
	}

}
