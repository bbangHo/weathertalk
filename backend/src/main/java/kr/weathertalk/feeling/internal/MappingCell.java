package kr.weathertalk.feeling.internal;

import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.MappingLayer;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 매핑 셀 하나 (04 §2): (온도 민감도 × 기상 조건 구간) → 범주별 대표 단계. 습도·바람은 그 범주의 가중 표본이 최소 표본 수 이상일 때만 있다.
 *
 * @param sampleCount 근거 표본 수(가중 전 건수)
 * @param weightedCount 온도 범주 가중 합
 */
record MappingCell(Key key, TemperatureTag temperature, HumidityTag humidity, WindTag wind, int sampleCount,
		double weightedCount) {

	/** 층마다 쓰지 않는 축은 null: 계절 없는 셀은 season, 축약 셀은 season·humidityBin·windBin. */
	record Key(MappingLayer layer, TemperatureSensitivity sensitivity, Season season, int temperatureBin,
			Integer humidityBin, Integer windBin) {

		static Key detail(TemperatureSensitivity sensitivity, Season season, int temperatureBin, int humidityBin,
				int windBin) {
			return new Key(MappingLayer.DETAIL, sensitivity, season, temperatureBin, humidityBin, windBin);
		}

		static Key noSeason(TemperatureSensitivity sensitivity, int temperatureBin, int humidityBin, int windBin) {
			return new Key(MappingLayer.NO_SEASON, sensitivity, null, temperatureBin, humidityBin, windBin);
		}

		static Key coarse(TemperatureSensitivity sensitivity, int temperatureBin) {
			return new Key(MappingLayer.COARSE, sensitivity, null, temperatureBin, null, null);
		}

	}

}
