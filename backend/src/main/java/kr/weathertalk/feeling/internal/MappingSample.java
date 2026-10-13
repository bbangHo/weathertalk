package kr.weathertalk.feeling.internal;

import java.time.Instant;

import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 매핑 재계산·보정 판정 입력 한 건: 관측 스냅샷이 '확보'인 체감 기록에서 필요한 값만 (FR-FEL-06 입력 데이터, BR-FEL-17 규칙 1).
 *
 * @param memberId 탈퇴(익명) 기록이면 null
 * @param humidityLevel 없을 수 있다(선택 범주, 피드백 기록은 항상 없음). windLevel도 같다
 */
record MappingSample(java.util.UUID memberId, TemperatureSensitivity sensitivity, Instant recordedAt,
		int temperatureLevel, Integer humidityLevel, Integer windLevel, FeelingRecordSource source, double temperature,
		double humidity, double windSpeed) {
}
