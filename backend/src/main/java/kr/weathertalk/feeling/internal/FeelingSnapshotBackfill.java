package kr.weathertalk.feeling.internal;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 미확보 관측 스냅샷 백필 (BR-FEL-05 마지막 항목). 기록 시각이 feeling.snapshot.backfill-window 안인 '미확보' 기록을
 * weather 과거 관측으로 다시 판정해 '확보'가 되면 채운다. 채운 기록은 다음 매핑 재계산·보정 판정부터 쓰인다.
 * 창을 지난 기록은 더 시도하지 않는다(weather 과거 관측 보관은 48시간까지만 보장).
 */
@Service
public class FeelingSnapshotBackfill {

	private static final Logger log = LoggerFactory.getLogger(FeelingSnapshotBackfill.class);

	/** @param candidates 창 안 미확보 기록 수, filled 채운 수 */
	public record Result(int candidates, int filled) {
	}

	private final FeelingRecordRepository records;
	private final SnapshotTaker snapshots;
	private final FeelingProperties properties;
	private final Clock clock;

	FeelingSnapshotBackfill(FeelingRecordRepository records, SnapshotTaker snapshots, FeelingProperties properties,
			Clock clock) {
		this.records = records;
		this.snapshots = snapshots;
		this.properties = properties;
		this.clock = clock;
	}

	public Result backfill() {
		Instant now = clock.instant();
		var missing = records.findMissingSnapshotsSince(now.minus(properties.snapshot().backfillWindow()));
		int filled = 0;
		for (StoredFeelingRecord record : missing) {
			ObservationSnapshot snapshot = snapshots.backfill(new NeighborhoodId(record.neighborhoodCode()),
					record.recordedAt());
			if (snapshot.secured() && records.fillMissingSnapshot(record.id(), record.recordedAt(), snapshot)) {
				filled++;
			}
		}
		if (!missing.isEmpty()) {
			log.info("관측 스냅샷 백필: 대상 {}건, 채움 {}건", missing.size(), filled);
		}
		return new Result(missing.size(), filled);
	}

}
