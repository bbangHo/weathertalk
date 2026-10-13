package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 메모리에 올린 매핑 버전 하나. 매핑 버전은 만들어진 뒤 바뀌지 않으므로(불변식 6) 버전 번호로 캐시해도 안전하다.
 * 셀 찾기는 BR-FEL-13 2단계 대체 순서: 세부 → 계절 없음 → 축약. 습도·풍속을 모르면 기온만 쓰는 축약 셀로 바로 간다.
 */
record LoadedMapping(int version, MappingSettings settings, Map<MappingCell.Key, MappingCell> cells) {

	Optional<MappingCell> find(TemperatureSensitivity sensitivity, Instant at, Double temperature, Double humidity,
			Double windSpeed) {
		if (temperature == null) {
			return Optional.empty();
		}
		int t = settings.temperatureBinOf(temperature);
		if (humidity != null && windSpeed != null) {
			int h = settings.humidityBinOf(humidity);
			int w = settings.windBinOf(windSpeed);
			MappingCell detail = cells.get(MappingCell.Key.detail(sensitivity, settings.seasonOf(at), t, h, w));
			if (detail != null) {
				return Optional.of(detail);
			}
			MappingCell noSeason = cells.get(MappingCell.Key.noSeason(sensitivity, t, h, w));
			if (noSeason != null) {
				return Optional.of(noSeason);
			}
		}
		return Optional.ofNullable(cells.get(MappingCell.Key.coarse(sensitivity, t)));
	}

}
