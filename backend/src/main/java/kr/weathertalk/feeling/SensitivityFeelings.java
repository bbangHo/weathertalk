package kr.weathertalk.feeling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 민감도별 체감 3그룹 일괄 결과 (FR-FEL-04). 그룹 순서는 더위를 많이 탐 → 보통 → 추위를 많이 탐.
 *
 * @param groups 산출된 그룹만 담는다. '산출 불가'(동네 체감 부족 + 기온 없음)인 그룹은 빠진다
 */
public record SensitivityFeelings(NeighborhoodId neighborhoodId, Instant basisTime, List<SensitivityFeeling> groups) {

	public Optional<SensitivityFeeling> find(TemperatureSensitivity sensitivity) {
		return groups.stream().filter(g -> g.temperatureSensitivity() == sensitivity).findFirst();
	}

	/** 세 그룹 모두 산출됐는가. */
	public boolean isComplete() {
		return groups.size() == TemperatureSensitivity.values().length;
	}

}
