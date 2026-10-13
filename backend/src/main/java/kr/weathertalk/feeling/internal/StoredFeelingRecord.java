package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.UUID;

import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.FeedbackAnswer;
import kr.weathertalk.feeling.FeelingRecordSource;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 저장된 체감 기록 (04 §3.2). 덮어쓰기 규칙(BR-FEL-04)은 {@link #overwrite}에 둔다: ID·생성 시각은 바꾸지 않고, 피드백이
 * 직접 기록을 덮어쓸 때만 습도·바람을 유지한다.
 *
 * @param memberId 탈퇴 후 null
 */
record StoredFeelingRecord(UUID id, UUID memberId, String neighborhoodCode, Instant createdAt, Instant recordedAt,
		TemperatureTag temperature, HumidityTag humidity, WindTag wind, TemperatureSensitivity sensitivity,
		FeelingRecordSource source, Feedback feedback, ObservationSnapshot snapshot) {

	/** 피드백 정보: 응답 원문, 보여준 온도 태그·산출 출처·매핑 버전. */
	record Feedback(FeedbackAnswer answer, TemperatureTag shownTemperature, DerivationSource shownSource,
			Integer shownMappingVersion) {
	}

	/** 새로 정해진 내용(직접 기록 또는 피드백 변환 결과). 피드백이면 feedback이 있고 습도·바람은 없다. */
	record Content(TemperatureTag temperature, HumidityTag humidity, WindTag wind, Feedback feedback) {

		boolean isFeedback() {
			return feedback != null;
		}

	}

	static StoredFeelingRecord create(UUID memberId, String neighborhoodCode, Instant now, Content content,
			TemperatureSensitivity sensitivity, ObservationSnapshot snapshot) {
		return new StoredFeelingRecord(UUID.randomUUID(), memberId, neighborhoodCode, now, now, content.temperature(),
				content.humidity(), content.wind(), sensitivity,
				content.isFeedback() ? FeelingRecordSource.FEEDBACK : FeelingRecordSource.DIRECT, content.feedback(),
				snapshot);
	}

	StoredFeelingRecord overwrite(Instant now, Content content, TemperatureSensitivity newSensitivity,
			ObservationSnapshot newSnapshot) {
		if (content.isFeedback() && source != FeelingRecordSource.FEEDBACK) {
			// 피드백이 직접 기록을 덮어쓰면 온도만 갱신(습도·바람 유지)
			return new StoredFeelingRecord(id, memberId, neighborhoodCode, createdAt, now, content.temperature(),
					humidity, wind, newSensitivity, FeelingRecordSource.DIRECT_WITH_FEEDBACK, content.feedback(),
					newSnapshot);
		}
		return new StoredFeelingRecord(id, memberId, neighborhoodCode, createdAt, now, content.temperature(),
				content.humidity(), content.wind(), newSensitivity,
				content.isFeedback() ? FeelingRecordSource.FEEDBACK : FeelingRecordSource.DIRECT, content.feedback(),
				newSnapshot);
	}

}
