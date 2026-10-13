package kr.weathertalk.shared.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생성성 쓰기를 (요청자, 요청 식별자) 단위로 한 번만 처리한다 (contracts.md §4).
 * <p>
 * 사용법 (컨트롤러):
 * <pre>{@code
 * @PostMapping("/api/v1/feeling-records")
 * FeelingRecordResponse create(Requester.Member member, IdempotencyKey key, @RequestBody Req req) {
 *     return idempotent.execute(member.memberId(), "feeling.record", key, FeelingRecordResponse.class,
 *             () -> service.record(member.memberId(), req));
 * }
 * }</pre>
 * 같은 키의 재요청에는 첫 결과(JSON 직렬화 후 복원)를 돌려주고, 첫 요청이 아직 처리 중이면 REQUEST_IN_PROGRESS를 던진다.
 * 처리 중 예외가 나면 표시를 지워 같은 키로 다시 시도할 수 있게 한다(실패는 보관하지 않는다).
 * <p>
 * 저장소 키에 작업 이름을 넣는 이유: 앱이 실수로 다른 종류의 요청에 같은 식별자를 쓰더라도 엉뚱한 결과를 돌려주지 않기 위해서다.
 */
@Component
public class IdempotentExecutor {

	private static final String KEY_PREFIX = "idem:";
	private static final String PROCESSING = "P:";
	private static final String DONE = "D:";

	// 처리 중 표시가 내 것일 때만 지운다. lease가 지나 다른 요청이 가져간 표시를 지우지 않기 위해서다.
	private static final RedisScript<Long> RELEASE_IF_OWNER = RedisScript.of(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);

	private final StringRedisTemplate redis;
	private final JsonMapper json;
	private final IdempotencyProperties properties;

	IdempotentExecutor(StringRedisTemplate redis, JsonMapper json, IdempotencyProperties properties) {
		this.redis = redis;
		this.json = json;
		this.properties = properties;
	}

	public <T> T execute(MemberId requester, String operation, IdempotencyKey key, Class<T> resultType,
			Supplier<T> action) {
		return execute(requester.value().toString(), operation, key, properties.ttl(), resultType, action);
	}

	public <T> T execute(MemberId requester, String operation, IdempotencyKey key, Duration ttl,
			Class<T> resultType, Supplier<T> action) {
		return execute(requester.value().toString(), operation, key, ttl, resultType, action);
	}

	/**
	 * @param requesterScope 요청자를 구분하는 값(회원 ID 등). 다른 요청자의 같은 키는 서로 영향이 없다.
	 * @param operation 작업 이름. 모듈 접두어를 붙인다(예: {@code community.post.create}).
	 */
	public <T> T execute(String requesterScope, String operation, IdempotencyKey key, Duration ttl,
			Class<T> resultType, Supplier<T> action) {
		String redisKey = KEY_PREFIX + operation + ":" + requesterScope + ":" + key.value();
		String marker = PROCESSING + UUID.randomUUID();

		// 표시가 사라지는 사이(만료·실패 정리)에 끼면 한 번 더 시도한다.
		for (int attempt = 0; attempt < 2; attempt++) {
			Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, marker, properties.processingLease());
			if (Boolean.TRUE.equals(acquired)) {
				return runAndStore(redisKey, marker, ttl, action);
			}
			String existing = redis.opsForValue().get(redisKey);
			if (existing == null) {
				continue;
			}
			if (existing.startsWith(PROCESSING)) {
				throw new DomainException(ErrorCode.REQUEST_IN_PROGRESS, "같은 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.");
			}
			return json.readValue(existing.substring(DONE.length()), resultType);
		}
		throw new DomainException(ErrorCode.REQUEST_IN_PROGRESS, "같은 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.");
	}

	private <T> T runAndStore(String redisKey, String marker, Duration ttl, Supplier<T> action) {
		T result;
		try {
			result = action.get();
		}
		catch (RuntimeException | Error e) {
			redis.execute(RELEASE_IF_OWNER, List.of(redisKey), marker);
			throw e;
		}
		redis.opsForValue().set(redisKey, DONE + json.writeValueAsString(result), ttl);
		return result;
	}

}
