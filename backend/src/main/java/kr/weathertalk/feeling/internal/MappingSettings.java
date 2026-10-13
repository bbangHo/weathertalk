package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.shared.time.TimeConfig;

/**
 * 매핑 버전에 함께 저장하는 설정값 (04 §3.5): 계절 구분·구간 정의·최소 표본 수·피드백 가중치. 구간을 바꾸면 다음 재계산부터 반영되고,
 * 조회는 그 버전을 만들 때의 구간으로 한다(BR-FEL-14).
 * <p>
 * 기온 구간 번호: 0 = temp-min 미만, 1.. = temp-min부터 temp-bin-width 간격, 마지막 = temp-max 이상(양 끝은 열린 구간).
 * 습도·풍속 구간 번호: 경계 목록에서 값 이하인 경계 수(경계값은 위 구간, "이상").
 */
record MappingSettings(int tempMin, int tempMax, int tempBinWidth, List<Integer> humidityBins, List<Double> windBins,
		Map<Season, List<Integer>> seasons, int minSamples, double feedbackWeight) {

	static MappingSettings from(FeelingProperties.Mapping mapping) {
		Map<Season, List<Integer>> seasons = new EnumMap<>(Season.class);
		seasons.put(Season.SPRING, List.copyOf(mapping.seasons().spring()));
		seasons.put(Season.SUMMER, List.copyOf(mapping.seasons().summer()));
		seasons.put(Season.AUTUMN, List.copyOf(mapping.seasons().autumn()));
		seasons.put(Season.WINTER, List.copyOf(mapping.seasons().winter()));
		return new MappingSettings(mapping.tempMin(), mapping.tempMax(), mapping.tempBinWidth(),
				List.copyOf(mapping.humidityBins()), List.copyOf(mapping.windBins()), seasons, mapping.minSamples(),
				mapping.feedbackWeight());
	}

	/** 기록 시각(조회는 기준 시각)의 한국 표준시 날짜로 계절을 정한다. */
	Season seasonOf(Instant at) {
		int month = at.atZone(TimeConfig.SEOUL).getMonthValue();
		return seasons.entrySet()
			.stream()
			.filter(e -> e.getValue().contains(month))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("계절 구분에 없는 월: " + month));
	}

	int temperatureBinOf(double temperature) {
		if (temperature < tempMin) {
			return 0;
		}
		int last = temperatureBinCount() - 1;
		if (temperature >= tempMax) {
			return last;
		}
		return Math.min(last - 1, 1 + (int) Math.floor((temperature - tempMin) / tempBinWidth));
	}

	int temperatureBinCount() {
		return 2 + (int) Math.ceil((double) (tempMax - tempMin) / tempBinWidth);
	}

	int humidityBinOf(double humidity) {
		int bin = 0;
		for (Integer bound : humidityBins) {
			if (humidity >= bound) {
				bin++;
			}
		}
		return bin;
	}

	int windBinOf(double windSpeed) {
		int bin = 0;
		for (Double bound : windBins) {
			if (windSpeed >= bound) {
				bin++;
			}
		}
		return bin;
	}

}
