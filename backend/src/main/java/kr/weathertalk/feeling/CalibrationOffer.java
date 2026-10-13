package kr.weathertalk.feeling;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 민감도 보정 제안 (04 §3.6, FR-FEL-07·08). 홈·마이페이지 조회로만 노출한다(푸시 없음, Q61).
 *
 * @param currentGroup 판정 당시 회원의 온도 민감도
 * @param suggestedGroup 권하는 온도 민감도
 * @param recordCount 판정에 쓴 기록 수(가중 전)
 * @param weightedRecordCount 판정에 쓴 기록의 가중 합
 * @param errors 그룹별 가중 평균 오차(단계). 작을수록 그 그룹 예상과 회원 기록이 가깝다
 */
public record CalibrationOffer(UUID offerId, TemperatureSensitivity currentGroup,
		TemperatureSensitivity suggestedGroup, int recordCount, double weightedRecordCount,
		Map<TemperatureSensitivity, Double> errors, Instant judgedAt, Instant expiresAt,
		CalibrationOfferStatus status) {
}
