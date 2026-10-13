package kr.weathertalk.outfit.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import kr.weathertalk.outfit.DepartureCondition;
import kr.weathertalk.outfit.DepartureOutfitRecommendation;
import kr.weathertalk.outfit.Outfit;
import kr.weathertalk.outfit.OutfitApi;
import kr.weathertalk.outfit.OutfitRecommendation;
import kr.weathertalk.outfit.Precipitation;
import kr.weathertalk.outfit.internal.config.OutfitProperties;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 옷 추천 (FR-OUT-01·02, BR-OUT-01~04). 온도 태그 단계만으로 정하며 기온 수치·체감온도를 보지 않는다.
 * 강수 보정은 소품만 더하고 겉옷은 바꾸지 않는다.
 */
@Service
public class OutfitService implements OutfitApi {

	private final OutfitProperties properties;

	public OutfitService(OutfitProperties properties) {
		this.properties = properties;
	}

	@Override
	public OutfitRecommendation recommend(int temperatureLevel, Precipitation precipitation) {
		OutfitProperties.Level level = properties.table()
			.levels()
			.stream()
			.filter(l -> l.level() == temperatureLevel)
			.findFirst()
			.orElseThrow(() -> new DomainException(ErrorCode.INVALID_REQUEST, "온도 체감 태그 단계는 1~7입니다."));
		Precipitation p = precipitation == null ? Precipitation.NONE : precipitation;
		List<String> accessories = new ArrayList<>(level.accessories());
		List<String> extra = switch (p) {
			case NONE -> List.of();
			case RAIN -> properties.table().rainAccessories();
			case SNOW -> properties.table().snowAccessories();
		};
		extra.stream().filter(item -> !accessories.contains(item)).forEach(accessories::add);
		Outfit outfit = new Outfit(List.copyOf(level.outer()), List.copyOf(level.top()), List.copyOf(level.bottom()),
				List.copyOf(accessories), level.guide());
		return new OutfitRecommendation(temperatureLevel, p, outfit, properties.table().version());
	}

	@Override
	public DepartureOutfitRecommendation recommendForDepartures(List<DepartureCondition> conditions) {
		if (conditions == null || conditions.isEmpty()) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "외출 시각 조건이 하나 이상 있어야 합니다.");
		}
		// 돌아올 때 춥지 않게 가장 추운 시각 기준(BR-OUT-03). 같으면 이른 시각.
		DepartureCondition coldest = conditions.stream()
			.min(Comparator.comparingInt(DepartureCondition::temperatureLevel)
				.thenComparing(DepartureCondition::departureAt, Comparator.nullsLast(Comparator.naturalOrder())))
			.orElseThrow();
		int warmest = conditions.stream().mapToInt(DepartureCondition::temperatureLevel).max().orElseThrow();
		Precipitation precipitation = conditions.stream()
			.map(DepartureCondition::precipitation)
			.filter(Objects::nonNull)
			.filter(p -> p != Precipitation.NONE)
			.max(Comparator.naturalOrder()) // 눈 > 비 (미끄럼 방지가 더 중요)
			.orElse(Precipitation.NONE);
		OutfitRecommendation recommendation = recommend(coldest.temperatureLevel(), precipitation);
		conditions.forEach(c -> recommend(c.temperatureLevel(), null)); // 범주 밖 단계 거부
		boolean layering = warmest - coldest.temperatureLevel() >= properties.layeringGap();
		return new DepartureOutfitRecommendation(recommendation, layering,
				layering ? properties.table().layeringGuide() : null, coldest.departureAt(),
				coldest.temperatureLevel());
	}

}
