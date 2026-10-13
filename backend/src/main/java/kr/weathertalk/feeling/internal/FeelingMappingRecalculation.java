package kr.weathertalk.feeling.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.FeedbackAnswer;
import kr.weathertalk.feeling.internal.config.FeelingProperties;

/**
 * 체감 매핑 재계산 (FR-FEL-06). 한 번 실행하면 새 매핑 버전 하나가 생긴다.
 * <ol>
 * <li>입력: 기록 시각이 feeling.mapping.lookback 안이고 관측 스냅샷이 '확보'인 기록 전체(미확보 제외, BR-FEL-05).</li>
 * <li>셀 계산·검증: {@link FeelingMappingCalculator}.</li>
 * <li>검증 통과 → 새 버전 '적용 중', 이전 적용 버전 '보관'. 실패 → '거부'하고 이전 버전을 유지한다.</li>
 * <li>보관 버전이 feeling.mapping.retained-versions를 넘으면 오래된 것부터 '폐기'(셀 삭제, 요약은 남김).</li>
 * <li>적중률: 직전 실행 이후의 피드백 기록에서 산출 출처·매핑 버전별 응답 분포와 '맞아요' 비율을 새 버전에 남긴다.</li>
 * </ol>
 * 한 트랜잭션이라 도중 오류면 아무것도 바뀌지 않고 이전 적용 버전이 유지된다. 다음 주기에 다시 돈다.
 */
@Service
public class FeelingMappingRecalculation {

	private static final Logger log = LoggerFactory.getLogger(FeelingMappingRecalculation.class);

	/** 실행 결과: 새 버전 번호와 최종 상태. */
	public record Result(int version, String status, String rejectReason) {
	}

	/** 적중률 한 줄 (ADR 0011): 보여준 값의 산출 출처·매핑 버전별 응답 분포. */
	record HitRate(DerivationSource shownSource, Integer shownMappingVersion, int total, int right, int colder,
			int hotter, double rightRatio) {
	}

	private final FeelingRecordRepository records;
	private final FeelingMappingRepository mappings;
	private final FeelingProperties properties;
	private final Clock clock;

	FeelingMappingRecalculation(FeelingRecordRepository records, FeelingMappingRepository mappings,
			FeelingProperties properties, Clock clock) {
		this.records = records;
		this.mappings = mappings;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public Result recalculate() {
		mappings.lock();
		Instant now = clock.instant();
		FeelingProperties.Mapping config = properties.mapping();
		Instant from = now.minus(config.lookback());
		MappingSettings settings = MappingSettings.from(config);

		FeelingMappingCalculator calculator = new FeelingMappingCalculator(settings, config.maxDroppedRatio());
		records.forEachSecuredSample(from, now, calculator::add);
		FeelingMappingCalculator.Result result = calculator.finish();
		List<HitRate> hitRate = hitRate(mappings.lastCreatedAt().filter(at -> at.isAfter(from)).orElse(from), now);

		int version = mappings.nextVersion();
		mappings.insertVersion(version, now, from, now, settings, result.validation(), hitRate);
		FeelingMappingCalculator.Validation validation = result.validation();
		if (!validation.passed()) {
			mappings.updateStatus(version, FeelingMappingRepository.Status.REJECTED, now);
			log.warn("체감 매핑 버전 {} 거부: {} (표본 {}건, 비운 축약 셀 {}개)", version, validation.rejectReason(),
					validation.sampleCount(), validation.droppedCoarseCells());
			return new Result(version, FeelingMappingRepository.Status.REJECTED.name(), validation.rejectReason());
		}
		mappings.insertCells(version, result.cells());
		mappings.appliedVersion()
			.ifPresent(previous -> mappings.updateStatus(previous, FeelingMappingRepository.Status.ARCHIVED, now));
		mappings.updateStatus(version, FeelingMappingRepository.Status.APPLIED, now);
		for (Integer old : mappings.archivedBeyond(config.retainedVersions())) {
			mappings.discard(old);
		}
		validation.warnings().forEach(warning -> log.warn("체감 매핑 버전 {}: {}", version, warning));
		log.info("체감 매핑 버전 {} 적용: 표본 {}건, 세부 {} / 계절 없음 {} / 축약 {} 셀", version, validation.sampleCount(),
				validation.detailCells(), validation.noSeasonCells(), validation.coarseCells());
		return new Result(version, FeelingMappingRepository.Status.APPLIED.name(), null);
	}

	private List<HitRate> hitRate(Instant fromExclusive, Instant toInclusive) {
		record Group(DerivationSource source, Integer version) {
		}
		Map<Group, Map<FeedbackAnswer, Integer>> grouped = new LinkedHashMap<>();
		for (FeelingRecordRepository.FeedbackCount row : records.countFeedbacks(fromExclusive, toInclusive)) {
			grouped.computeIfAbsent(new Group(row.shownSource(), row.shownMappingVersion()), g -> new LinkedHashMap<>())
				.merge(row.answer(), row.count(), Integer::sum);
		}
		List<HitRate> rows = new ArrayList<>();
		grouped.forEach((group, answers) -> {
			int right = answers.getOrDefault(FeedbackAnswer.RIGHT, 0);
			int colder = answers.getOrDefault(FeedbackAnswer.COLDER, 0);
			int hotter = answers.getOrDefault(FeedbackAnswer.HOTTER, 0);
			int total = right + colder + hotter;
			rows.add(new HitRate(group.source(), group.version(), total, right, colder, hotter,
					total == 0 ? 0 : (double) right / total));
		});
		rows.sort(java.util.Comparator.comparing((HitRate r) -> Objects.toString(r.shownSource()))
			.thenComparing(r -> r.shownMappingVersion() == null ? 0 : r.shownMappingVersion()));
		return rows;
	}

}
