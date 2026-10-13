package kr.weathertalk.feeling.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.FeelingRecordOwnership;
import kr.weathertalk.feeling.FeelingRecordView;
import kr.weathertalk.feeling.MyFeelingRecords;
import kr.weathertalk.feeling.NeighborhoodFeeling;
import kr.weathertalk.feeling.RecentFeelingRecord;
import kr.weathertalk.feeling.SensitivityFeeling;
import kr.weathertalk.feeling.SensitivityFeelings;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.YesterdayComparison;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * {@link FeelingApi} 구현 (FR-FEL-03·04·05·10·11).
 */
@Service
class FeelingApiService implements FeelingApi {

	private static final Duration ONE_DAY = Duration.ofHours(24);

	private final SensitivityFeelingDeriver deriver;
	private final NeighborhoodFeelingAggregator aggregator;
	private final FeelingRecordRepository records;
	private final ReferenceTable referenceTable;
	private final NeighborhoodApi neighborhoodApi;
	private final FeelingProperties properties;
	private final Clock clock;

	FeelingApiService(SensitivityFeelingDeriver deriver, NeighborhoodFeelingAggregator aggregator,
			FeelingRecordRepository records, ReferenceTable referenceTable, NeighborhoodApi neighborhoodApi,
			FeelingProperties properties, Clock clock) {
		this.deriver = deriver;
		this.aggregator = aggregator;
		this.records = records;
		this.referenceTable = referenceTable;
		this.neighborhoodApi = neighborhoodApi;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public Optional<SensitivityFeeling> findSensitivityFeeling(NeighborhoodId neighborhoodId,
			TemperatureSensitivity sensitivity, Instant at) {
		requireNeighborhood(neighborhoodId);
		return deriver.derive(neighborhoodId, sensitivity, at);
	}

	@Override
	public SensitivityFeelings findSensitivityFeelings(NeighborhoodId neighborhoodId, Instant at) {
		requireNeighborhood(neighborhoodId);
		return deriver.deriveAll(neighborhoodId, at);
	}

	@Override
	public NeighborhoodFeeling findNeighborhoodFeeling(NeighborhoodId neighborhoodId, Instant at) {
		requireNeighborhood(neighborhoodId);
		return aggregator.toView(neighborhoodId, aggregator.aggregate(neighborhoodId, at == null ? clock.instant() : at));
	}

	/** FR-FEL-05: 오늘 T와 어제 T − 24시간을 같은 방식(FR-FEL-04)으로 산출해 비교한다. */
	@Override
	public YesterdayComparison compareWithYesterday(NeighborhoodId neighborhoodId, TemperatureSensitivity sensitivity,
			Instant at) {
		requireNeighborhood(neighborhoodId);
		Instant today = at == null ? clock.instant() : at;
		Optional<SensitivityFeeling> todayFeeling = deriver.derive(neighborhoodId, sensitivity, today);
		Optional<SensitivityFeeling> yesterdayFeeling = deriver.derive(neighborhoodId, sensitivity,
				today.minus(ONE_DAY));
		if (todayFeeling.isEmpty() || yesterdayFeeling.isEmpty()) {
			return new YesterdayComparison(neighborhoodId, sensitivity, false, null, null, todayFeeling.orElse(null),
					yesterdayFeeling.orElse(null));
		}
		TemperatureTag a = todayFeeling.get().temperatureTag();
		TemperatureTag b = yesterdayFeeling.get().temperatureTag();
		return new YesterdayComparison(neighborhoodId, sensitivity, true, a.level() - b.level(),
				ComparisonPhrase.of(a, b, properties.comparison().largeDiff()), todayFeeling.get(),
				yesterdayFeeling.get());
	}

	@Override
	public FeelingRecordOwnership checkOwnership(MemberId memberId, FeelingRecordId recordId) {
		return records.findById(recordId.value())
			.filter(r -> memberId.value().equals(r.memberId()))
			.map(r -> new FeelingRecordOwnership(true, new NeighborhoodId(r.neighborhoodCode()), r.recordedAt()))
			.orElseGet(FeelingRecordOwnership::notOwned);
	}

	@Override
	public Map<FeelingRecordId, FeelingRecordView> findRecords(Collection<FeelingRecordId> recordIds) {
		return records.findByIds(recordIds.stream().map(FeelingRecordId::value).distinct().toList())
			.stream()
			.collect(Collectors.toMap(r -> new FeelingRecordId(r.id()),
					r -> new FeelingRecordView(new FeelingRecordId(r.id()), r.temperature(), r.humidity(), r.wind(),
							r.recordedAt(), new NeighborhoodId(r.neighborhoodCode()), r.sensitivity())));
	}

	@Override
	public Optional<RecentFeelingRecord> findRecentRecord(MemberId memberId, NeighborhoodId neighborhoodId) {
		Instant now = clock.instant();
		return records.findCreatedAfter(memberId.value(), neighborhoodId.value(), now.minus(properties.recordInterval()))
			.map(r -> new RecentFeelingRecord(new FeelingRecordId(r.id()), r.source(), r.temperature(),
					r.recordedAt(), r.createdAt(), r.createdAt().plus(properties.recordInterval())));
	}

	@Override
	public MyFeelingRecords findMyRecords(MemberId memberId) {
		Instant now = clock.instant();
		List<StoredFeelingRecord> mine = records.findByMemberRecordedAfter(memberId.value(),
				now.minus(properties.myRecords().period()));
		Map<NeighborhoodId, Neighborhood> neighborhoods = neighborhoodApi
			.findNeighborhoods(mine.stream().map(r -> new NeighborhoodId(r.neighborhoodCode())).distinct().toList());
		List<MyFeelingRecords.Item> items = mine.stream().map(r -> {
			NeighborhoodId id = new NeighborhoodId(r.neighborhoodCode());
			Neighborhood n = neighborhoods.get(id);
			return new MyFeelingRecords.Item(new FeelingRecordId(r.id()), r.recordedAt(), id,
					n == null ? null : n.shortName(), r.temperature(), r.humidity(), r.wind(), r.source(),
					r.sensitivity());
		}).toList();
		return new MyFeelingRecords(items, pattern(mine));
	}

	/**
	 * 내 체감 패턴 (FR-FEL-11 규칙 3). 경향은 관측 스냅샷 '확보' 기록마다 (기록 온도 단계 − 기록 시점 그룹의 예상 단계)의 평균.
	 * 예상 단계는 적용 중인 체감 매핑(M6), 없으면 고정 기준표로 구한다. 자기 기록이 섞이는 동네 체감은 쓰지 않는다.
	 */
	private MyFeelingRecords.Pattern pattern(List<StoredFeelingRecord> mine) {
		Map<TemperatureTag, Integer> distribution = new LinkedHashMap<>();
		for (TemperatureTag tag : TemperatureTag.values()) {
			distribution.put(tag, 0);
		}
		mine.forEach(r -> distribution.merge(r.temperature(), 1, Integer::sum));
		List<Integer> differences = mine.stream()
			.filter(r -> r.snapshot().secured() && r.snapshot().temperature() != null)
			.map(r -> r.temperature().level()
					- referenceTable.tagFor(r.sensitivity(), r.snapshot().temperature()).level())
			.toList();
		if (differences.size() < properties.myPattern().minRecords()) {
			return new MyFeelingRecords.Pattern(mine.size(), distribution, MyFeelingRecords.Tendency.INSUFFICIENT_RECORDS,
					"기록이 더 쌓이면 내 체감 경향을 알려 드릴게요.", null, differences.size());
		}
		double average = differences.stream().mapToInt(Integer::intValue).average().orElse(0);
		double threshold = properties.myPattern().tendencyThreshold();
		MyFeelingRecords.Tendency tendency = average <= -threshold ? MyFeelingRecords.Tendency.FEELS_COLDER
				: average >= threshold ? MyFeelingRecords.Tendency.FEELS_HOTTER : MyFeelingRecords.Tendency.SIMILAR;
		String phrase = switch (tendency) {
			case FEELS_COLDER -> "같은 그룹보다 춥게 느끼는 편이에요.";
			case FEELS_HOTTER -> "같은 그룹보다 덥게 느끼는 편이에요.";
			default -> "같은 그룹과 비슷하게 느끼는 편이에요.";
		};
		return new MyFeelingRecords.Pattern(mine.size(), distribution, tendency, phrase, average, differences.size());
	}

	private void requireNeighborhood(NeighborhoodId neighborhoodId) {
		if (neighborhoodApi.findNeighborhood(neighborhoodId).isEmpty()) {
			throw new DomainException(ErrorCode.NOT_FOUND, "없는 동네입니다.");
		}
	}

}
