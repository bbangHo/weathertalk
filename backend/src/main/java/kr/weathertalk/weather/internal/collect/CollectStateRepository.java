package kr.weathertalk.weather.internal.collect;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 수집 상태 저장 (03 §3.2): 마지막 정상 발표 시각, 마지막 시도 시각·결과, 연속 실패 수. */
@Repository
public class CollectStateRepository {

	public enum Result {

		SUCCESS,

		/** 최신 발표분이 비어 직전 발표분을 받았다(BR-WEA-06). */
		SUBSTITUTE,

		/** 발표분 미제공. */
		NOT_READY,

		FAILURE,

		/** 인증키 미설정. */
		KEY_MISSING

	}

	public record CollectState(CollectionKind kind, String unitKey, Instant lastSuccessIssuedAt, Instant lastAttemptAt,
			Instant lastAttemptTarget, Result lastResult, int consecutiveFailures) {
	}

	private final JdbcClient jdbc;

	public CollectStateRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<CollectState> find(CollectionKind kind, String unitKey) {
		return jdbc.sql("""
				SELECT kind, unit_key, last_success_issued_at, last_attempt_at, last_attempt_target, last_result,
				       consecutive_failures
				FROM weather_collect_state WHERE kind = :kind AND unit_key = :unit
				""")
			.param("kind", kind.name())
			.param("unit", unitKey)
			.query((rs, n) -> new CollectState(CollectionKind.valueOf(rs.getString("kind")), rs.getString("unit_key"),
					instant(rs.getTimestamp("last_success_issued_at")), instant(rs.getTimestamp("last_attempt_at")),
					instant(rs.getTimestamp("last_attempt_target")),
					rs.getString("last_result") == null ? null : Result.valueOf(rs.getString("last_result")),
					rs.getInt("consecutive_failures")))
			.optional();
	}

	/**
	 * @param issuedAt 성공·대체면 받은 발표 시각, 아니면 null
	 */
	public void record(CollectionKind kind, String unitKey, Instant target, Instant issuedAt, Result result,
			Instant attemptedAt) {
		boolean success = result == Result.SUCCESS || result == Result.SUBSTITUTE;
		jdbc.sql("""
				INSERT INTO weather_collect_state (kind, unit_key, last_success_issued_at, last_attempt_at,
				                                   last_attempt_target, last_result, consecutive_failures)
				VALUES (:kind, :unit, :issued, :attempted, :target, :result, :failures)
				ON CONFLICT (kind, unit_key) DO UPDATE SET
				    last_success_issued_at = COALESCE(EXCLUDED.last_success_issued_at, weather_collect_state.last_success_issued_at),
				    last_attempt_at = EXCLUDED.last_attempt_at,
				    last_attempt_target = EXCLUDED.last_attempt_target,
				    last_result = EXCLUDED.last_result,
				    consecutive_failures = CASE WHEN :success THEN 0 ELSE weather_collect_state.consecutive_failures + 1 END
				""")
			.param("kind", kind.name())
			.param("unit", unitKey)
			.param("issued", success ? timestamp(issuedAt) : null, java.sql.Types.TIMESTAMP)
			.param("attempted", timestamp(attemptedAt))
			.param("target", timestamp(target), java.sql.Types.TIMESTAMP)
			.param("result", result.name())
			.param("failures", success ? 0 : 1)
			.param("success", success)
			.update();
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
