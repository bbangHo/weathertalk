package kr.weathertalk.feeling;

import static kr.weathertalk.neighborhood.NeighborhoodTestData.N1;
import static kr.weathertalk.weather.WeatherStubs.kst;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import kr.weathertalk.IntegrationTest;
import kr.weathertalk.feeling.internal.FeelingMappingRecalculation;
import kr.weathertalk.feeling.internal.FeelingSnapshotBackfill;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 미확보 관측 스냅샷 백필 (BR-FEL-05 마지막 항목, M3에서 M6로 옮김). feeling.snapshot.backfill-window(24시간) 안의 '미확보' 기록만
 * weather 과거 관측으로 다시 판정한다. 관측이 저장돼 있고 기록 시각과 feeling.snapshot.max-age(90분) 안이면 '확보'로 채운다.
 */
@IntegrationTest
class FeelingSnapshotBackfillTests extends FeelingTestBase {

	@Autowired
	FeelingSnapshotBackfill backfill;

	@Autowired
	FeelingMappingRecalculation recalculation;

	/** 그 시각에 현재 관측을 한 번 조회해 weather에 관측을 저장해 둔다(과거 관측 조회는 저장분만 쓴다). */
	private void storeObservation(Instant base, double temperature) {
		clock.set(base.plusSeconds(15 * 60));
		stubs.observation(gridOf(N1), base, temperature);
		weatherApi.findCurrentObservation(N1);
	}

	private UUID missingRecord(Instant recordedAt) {
		return insertRecord(N1, TemperatureSensitivity.NORMAL, recordedAt, 4);
	}

	private Map<String, Object> snapshot(UUID id) {
		return jdbc.sql("SELECT snapshot_status, snapshot_temperature, snapshot_humidity, snapshot_wind_speed, "
				+ "snapshot_observed_at FROM feeling_record WHERE id = :id")
			.param("id", id)
			.query()
			.singleRow();
	}

	@Test
	void missingSnapshotsWithinWindowAreFilledFromPastObservation() {
		storeObservation(kst("2026-10-12T09:00"), 11.0);
		storeObservation(kst("2026-10-13T09:00"), 18.0);

		UUID inWindow = missingRecord(kst("2026-10-13T09:20"));
		UUID tooOldObservation = missingRecord(kst("2026-10-13T07:10")); // 그 시각 이전 관측은 하루 전 것뿐 → 90분 초과
		UUID outsideWindow = missingRecord(kst("2026-10-12T09:10")); // 관측은 있으나 백필 창(24시간) 밖
		clock.set(kst("2026-10-13T10:00"));

		FeelingSnapshotBackfill.Result result = backfill.backfill();

		assertThat(result.candidates()).isEqualTo(2);
		assertThat(result.filled()).isEqualTo(1);
		Map<String, Object> filled = snapshot(inWindow);
		assertThat(filled.get("snapshot_status")).isEqualTo("SECURED");
		assertThat(filled.get("snapshot_temperature")).isEqualTo(18.0);
		assertThat(filled.get("snapshot_humidity")).isEqualTo(60.0);
		assertThat(filled.get("snapshot_wind_speed")).isEqualTo(2.0);
		assertThat(snapshot(tooOldObservation).get("snapshot_status")).isEqualTo("MISSING");
		assertThat(snapshot(outsideWindow).get("snapshot_status")).isEqualTo("MISSING");

		// 다시 돌려도 이미 채운 기록은 건드리지 않는다
		assertThat(backfill.backfill().filled()).isZero();
	}

	/** 백필로 '확보'가 된 기록은 다음 매핑 재계산부터 입력에 들어간다. */
	@Test
	void backfilledRecordsJoinNextMappingRecalculation() {
		storeObservation(kst("2026-10-13T09:00"), 20.0);
		for (int i = 0; i < 30; i++) {
			insertRecord(N1, TemperatureSensitivity.NORMAL, kst("2026-10-13T09:20").plusSeconds(i), 5);
		}
		clock.set(kst("2026-10-13T10:00"));
		assertThat(recalculation.recalculate().status()).isEqualTo("REJECTED"); // 확보 기록 없음 → 채운 셀 없음

		assertThat(backfill.backfill().filled()).isEqualTo(30);
		clock.set(kst("2026-10-13T10:05"));
		assertThat(recalculation.recalculate().status()).isEqualTo("APPLIED");
	}

}
