package kr.weathertalk.feeling;

import java.time.Instant;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/** 체감 기록 조회 결과 (FR-FEL-10, 게시글 첨부 표시용). 회원 ID는 담지 않는다. */
public record FeelingRecordView(FeelingRecordId id, TemperatureTag temperatureTag, HumidityTag humidityTag,
		WindTag windTag, Instant recordedAt, NeighborhoodId neighborhoodId,
		TemperatureSensitivity temperatureSensitivity) {
}
