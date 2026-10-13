package kr.weathertalk.weather.internal.collect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 수집 진행 점유 (BR-WEA-07): (수집 단위, 데이터 종류, 발표 시각)마다 인스턴스 간 공유 저장소(Redis)에서 원자적으로 얻고,
 * 만료 시각(weather.collect.lock-lease)을 가져 수집자가 죽어도 풀린다.
 * <p>
 * Redis 장애면 {@link Status#UNAVAILABLE}을 돌려 호출자가 점유 없이 원천 수집으로 대체한다 — 저장소 장애를 사용자 오류로 전파하지 않는다(legacy 06-#4).
 * 이때 중복 수집이 생길 수 있지만 같은 발표분은 통째 교체 저장이라 결과는 같다.
 */
@Component
public class CollectLock {

	private static final Logger log = LoggerFactory.getLogger(CollectLock.class);

	private static final String PREFIX = "weather:collect:";

	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);

	public enum Status {

		ACQUIRED, HELD_BY_OTHER, UNAVAILABLE

	}

	public final class Handle implements AutoCloseable {

		private final Status status;
		private final String key;
		private final String token;

		private Handle(Status status, String key, String token) {
			this.status = status;
			this.key = key;
			this.token = token;
		}

		public Status status() {
			return status;
		}

		public String key() {
			return key;
		}

		@Override
		public void close() {
			if (status != Status.ACQUIRED) {
				return;
			}
			try {
				redis.execute(RELEASE, List.of(key), token);
			}
			catch (RuntimeException e) {
				// 못 풀어도 lease가 지나면 풀린다
				log.warn("수집 점유 해제 실패({}): {}", key, e.getClass().getSimpleName());
			}
		}

	}

	private final StringRedisTemplate redis;

	public CollectLock(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public static String key(CollectionKind kind, String unitKey, Instant target) {
		return PREFIX + kind.name() + ":" + unitKey + ":" + target.getEpochSecond();
	}

	public Handle tryAcquire(CollectionKind kind, String unitKey, Instant target, Duration lease) {
		String key = key(kind, unitKey, target);
		String token = UUID.randomUUID().toString();
		try {
			Boolean acquired = redis.opsForValue().setIfAbsent(key, token, lease);
			return new Handle(Boolean.TRUE.equals(acquired) ? Status.ACQUIRED : Status.HELD_BY_OTHER, key, token);
		}
		catch (RuntimeException e) {
			log.warn("수집 점유 저장소 사용 불가 — 점유 없이 수집합니다({}): {}", key, e.getClass().getSimpleName());
			return new Handle(Status.UNAVAILABLE, key, token);
		}
	}

	/** 저장소 장애면 false(기다리지 않고 저장값으로 응답). */
	public boolean isHeld(String key) {
		try {
			return Boolean.TRUE.equals(redis.hasKey(key));
		}
		catch (RuntimeException e) {
			return false;
		}
	}

}
