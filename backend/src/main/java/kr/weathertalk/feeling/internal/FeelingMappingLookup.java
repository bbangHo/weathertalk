package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 민감도별 체감 2단계(체감 매핑) 자리 (BR-FEL-13). 매핑 재계산·버전(FR-FEL-06)은 M6에서 구현하며, 그때 이 구현을 적용 중인
 * 매핑 버전의 셀 조회(세부 → 계절 없음 → 축약)로 바꾼다. 그 전까지는 적용 중인 버전이 없는 것과 같다(2단계를 건너뜀).
 * 호출 여부는 feeling.mapping.enabled 기능 플래그가 정한다.
 */
@Component
class FeelingMappingLookup {

	/** @param mappingVersion 사용한 매핑 버전, sampleCount 셀의 근거 표본 수 */
	record Cell(TemperatureTag temperature, HumidityTag humidity, WindTag wind, int mappingVersion, int sampleCount) {
	}

	Optional<Cell> find(TemperatureSensitivity sensitivity, Instant basisTime, Double temperature, Double humidity,
			Double windSpeed) {
		return Optional.empty();
	}

}
