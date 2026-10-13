package kr.weathertalk.weather.internal.precip;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 강수 구간 발행 기록 저장 (03 §3.9, BR-WEA-20). */
@Repository
public class PrecipEpisodeRepository {

	/** @param lastWetAt 구간 시작 후 마지막으로 강수가 확인된 시각. 없으면 null */
	record Episode(Instant expectedStartAt, Instant publishedAt, Instant basisIssuedAt, Instant lastWetAt) {
	}

	private final JdbcClient jdbc;

	PrecipEpisodeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void lock(String neighborhoodCode) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "weather.precip-start:" + neighborhoodCode)
			.query((rs, n) -> 1)
			.single();
	}

	List<Episode> find(String neighborhoodCode) {
		return jdbc.sql("SELECT expected_start_at, published_at, basis_issued_at, last_wet_at FROM weather_precip_episode "
				+ "WHERE neighborhood_code = :nbr ORDER BY expected_start_at")
			.param("nbr", neighborhoodCode)
			.query((rs, n) -> new Episode(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(),
					rs.getTimestamp(3).toInstant(),
					rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()))
			.list();
	}

	void insert(String neighborhoodCode, Instant expectedStartAt, Instant publishedAt, Instant basisIssuedAt) {
		jdbc.sql("""
				INSERT INTO weather_precip_episode (neighborhood_code, expected_start_at, published_at, basis_issued_at)
				VALUES (:nbr, :start, :published, :basis)
				""")
			.param("nbr", neighborhoodCode)
			.param("start", Timestamp.from(expectedStartAt))
			.param("published", Timestamp.from(publishedAt))
			.param("basis", Timestamp.from(basisIssuedAt))
			.update();
	}

	/** 시작한 구간에 지금 강수가 확인됐다고 기록한다. */
	void markWet(String neighborhoodCode, Instant now) {
		jdbc.sql("UPDATE weather_precip_episode SET last_wet_at = :now WHERE neighborhood_code = :nbr "
				+ "AND expected_start_at <= :now")
			.param("nbr", neighborhoodCode)
			.param("now", Timestamp.from(now))
			.update();
	}

	void delete(String neighborhoodCode, Instant expectedStartAt) {
		jdbc.sql("DELETE FROM weather_precip_episode WHERE neighborhood_code = :nbr AND expected_start_at = :start")
			.param("nbr", neighborhoodCode)
			.param("start", Timestamp.from(expectedStartAt))
			.update();
	}

	/** 예상 시작 시각이 cutoff 이전(포함)인 기록 — 예상 시작 + episode-expiry가 지난 기록을 지운다. nbr이 null이면 전체. */
	public int deleteStartedAtOrBefore(String neighborhoodCode, Instant cutoff) {
		if (neighborhoodCode == null) {
			return jdbc.sql("DELETE FROM weather_precip_episode WHERE expected_start_at <= :cutoff")
				.param("cutoff", Timestamp.from(cutoff))
				.update();
		}
		return jdbc.sql("DELETE FROM weather_precip_episode WHERE neighborhood_code = :nbr AND expected_start_at <= :cutoff")
			.param("nbr", neighborhoodCode)
			.param("cutoff", Timestamp.from(cutoff))
			.update();
	}

}
