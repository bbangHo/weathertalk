package kr.weathertalk.feeling.internal;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 고정 기준표 (BR-FEL-18): 원 기온(℃, 체감온도 아님) + 온도 민감도 → 온도 태그. 위 단계부터 먼저 만족하는 단계, 경계는 "이상".
 * 경계 수치는 feeling.reference-table 설정이며, 설정 검증이 단계 순서와의 단조 일치를 보장한다(legacy 03-#13).
 */
@Component
class ReferenceTable {

	private final FeelingProperties.ReferenceTable table;

	@Autowired
	ReferenceTable(FeelingProperties properties) {
		this(properties.referenceTable());
	}

	ReferenceTable(FeelingProperties.ReferenceTable table) {
		this.table = table;
	}

	TemperatureTag tagFor(TemperatureSensitivity sensitivity, double temperature) {
		List<Double> lowerBounds = switch (sensitivity) {
			case HEAT_SENSITIVE -> table.heatSensitive();
			case NORMAL -> table.normal();
			case COLD_SENSITIVE -> table.coldSensitive();
		};
		// 하한 목록은 7단계부터 2단계 순서
		for (int i = 0; i < lowerBounds.size(); i++) {
			if (temperature >= lowerBounds.get(i)) {
				return TemperatureTag.of(TemperatureTag.VERY_HOT.level() - i);
			}
		}
		return TemperatureTag.VERY_COLD;
	}

}
