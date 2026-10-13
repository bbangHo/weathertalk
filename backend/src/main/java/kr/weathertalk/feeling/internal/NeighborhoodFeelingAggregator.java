package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;

import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.CategorySummaries;
import kr.weathertalk.feeling.CategorySummary;
import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.feeling.FeelingTag;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.NeighborhoodFeeling;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네 체감 집계 (FR-FEL-03, BR-FEL-08~11). 저장하지 않고 조회 때 계산하므로 새 기록이 다음 조회에 바로 반영된다(BR-FEL-12).
 * <ul>
 * <li>대상: 동네가 같고 기록 시각이 (기준 시각 − feeling.neighborhood-window, 기준 시각]. 탈퇴(익명) 기록 포함.</li>
 * <li>그룹은 기록 시점 온도 민감도. 범주·그룹마다 독립 계산하며 빈 것은 '기록 없음'(실패 아님, legacy 03-#12).</li>
 * <li>가중치: 피드백 = feeling.neighborhood.feedback-weight, 직접 = 1, 직접+피드백 보정은 온도만 피드백 가중치. 표본 수는 가중 전 건수.</li>
 * </ul>
 */
@Component
class NeighborhoodFeelingAggregator {

	/** 한 범주의 집계 원본. median은 표시 기준과 무관하게 계산한 가중 중앙값(표본 없으면 null). */
	record Category(int count, Map<Integer, Integer> countByLevel, Integer median) {
	}

	record Group(Category temperature, Category humidity, Category wind) {
	}

	record Aggregation(Instant basisTime, Instant windowStart, Group overall, Map<TemperatureSensitivity, Group> groups) {
	}

	private final FeelingRecordRepository records;
	private final FeelingProperties properties;

	NeighborhoodFeelingAggregator(FeelingRecordRepository records, FeelingProperties properties) {
		this.records = records;
		this.properties = properties;
	}

	Aggregation aggregate(NeighborhoodId neighborhoodId, Instant basisTime) {
		Instant windowStart = basisTime.minus(properties.neighborhoodWindow());
		List<StoredFeelingRecord> rows = records.findInWindow(neighborhoodId.value(), windowStart, basisTime);
		Map<TemperatureSensitivity, Group> groups = new EnumMap<>(TemperatureSensitivity.class);
		for (TemperatureSensitivity sensitivity : TemperatureSensitivity.values()) {
			groups.put(sensitivity, group(rows.stream().filter(r -> r.sensitivity() == sensitivity).toList()));
		}
		return new Aggregation(basisTime, windowStart, group(rows), groups);
	}

	NeighborhoodFeeling toView(NeighborhoodId neighborhoodId, Aggregation aggregation) {
		Map<TemperatureSensitivity, CategorySummaries> groups = new EnumMap<>(TemperatureSensitivity.class);
		aggregation.groups().forEach((sensitivity, group) -> groups.put(sensitivity, summaries(group)));
		return new NeighborhoodFeeling(neighborhoodId, aggregation.basisTime(), aggregation.windowStart(),
				summaries(aggregation.overall()), groups);
	}

	private Group group(List<StoredFeelingRecord> rows) {
		double feedbackWeight = properties.neighborhood().feedbackWeight();
		return new Group(
				category(rows, r -> r.temperature(), r -> r.source() == FeelingRecordSource.DIRECT ? 1.0 : feedbackWeight,
						TemperatureTag.NEUTRAL.level()),
				category(rows, r -> r.humidity(),
						r -> r.source() == FeelingRecordSource.FEEDBACK ? feedbackWeight : 1.0,
						HumidityTag.NEUTRAL.level()),
				category(rows, r -> r.wind(), r -> r.source() == FeelingRecordSource.FEEDBACK ? feedbackWeight : 1.0,
						WindTag.NEUTRAL.level()));
	}

	private static Category category(List<StoredFeelingRecord> rows, Function<StoredFeelingRecord, FeelingTag> tag,
			ToDoubleFunction<StoredFeelingRecord> weight, int neutral) {
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		Map<Integer, Double> weights = new LinkedHashMap<>();
		int count = 0;
		for (StoredFeelingRecord row : rows) {
			FeelingTag value = tag.apply(row);
			if (value == null) {
				continue;
			}
			count++;
			counts.merge(value.level(), 1, Integer::sum);
			weights.merge(value.level(), weight.applyAsDouble(row), Double::sum);
		}
		return new Category(count, counts, WeightedMedian.of(weights, neutral));
	}

	private CategorySummaries summaries(Group group) {
		return new CategorySummaries(summary(group.temperature(), TemperatureTag.values(), TemperatureTag::of),
				summary(group.humidity(), HumidityTag.values(), HumidityTag::of),
				summary(group.wind(), WindTag.values(), WindTag::of));
	}

	private <T extends FeelingTag> CategorySummary<T> summary(Category category, T[] all, IntFunction<T> of) {
		Map<T, Integer> distribution = new LinkedHashMap<>();
		for (T tag : all) {
			distribution.put(tag, category.countByLevel().getOrDefault(tag.level(), 0));
		}
		if (category.count() == 0) {
			return new CategorySummary<>(0, distribution, null, CategorySummary.Status.NO_RECORDS);
		}
		boolean enough = category.count() >= properties.neighborhood().minDisplayRecords()
				&& category.median() != null;
		return new CategorySummary<>(category.count(), distribution, enough ? of.apply(category.median()) : null,
				enough ? CategorySummary.Status.OK : CategorySummary.Status.INSUFFICIENT_RECORDS);
	}

}
