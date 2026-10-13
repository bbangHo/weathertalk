package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 체감 기록·피드백 저장 결과(응답 본문). 같은 요청 식별자의 재요청에는 이 값이 그대로 돌아간다.
 *
 * @param overwritten 기존 기록을 덮어썼는가(아니면 새 기록)
 * @param overwritableUntil 이 시각 전에 다시 남기면 이 기록을 덮어쓴다(생성 시각 + feeling.record-interval)
 * @param snapshotSecured 관측 스냅샷 확보 여부
 */
public record FeelingRecordResult(UUID recordId, String neighborhoodId, Instant createdAt, Instant recordedAt,
		TemperatureTag temperatureTag, HumidityTag humidityTag, WindTag windTag,
		TemperatureSensitivity temperatureSensitivity, FeelingRecordSource source, boolean snapshotSecured,
		boolean overwritten, Instant overwritableUntil) {
}
