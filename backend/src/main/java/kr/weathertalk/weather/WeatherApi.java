package kr.weathertalk.weather;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * weather 모듈의 공개 인터페이스 (contracts.md §2.3). 다른 모듈은 이 타입으로만 weather 기능을 사용한다.
 * <p>
 * 조회는 외부 장애로 오류를 내지 않는다: 수집 실패면 마지막 정상값과 신선도 '지연'/'없음'으로 응답한다.
 * 현장 제보 하기는 앱 직접 진입점(웹 API)에서만 받는다 — 요청 식별자 멱등·쓰기 직전 현재 동네 확정이 함께 필요해서다.
 *
 * @see kr.weathertalk.shared.error.ErrorCode#NOT_FOUND 동네가 없을 때
 */
public interface WeatherApi {

	/** 현재 관측 조회 (FR-WEA-01). 필요하면 요청 시 수집한다. */
	CurrentObservation findCurrentObservation(NeighborhoodId neighborhoodId);

	/** 과거 관측 조회 (FR-WEA-13). 저장분만 쓰며(요청 시 수집 없음) 48시간 이내는 보관을 보장한다. */
	CurrentObservation findPastObservation(NeighborhoodId neighborhoodId, Instant at);

	/** 시간별 예보 조회 (FR-WEA-02). @param hours 조회 범위 시간 수. null이면 weather.short-term.default-hours */
	HourlyForecast findHourlyForecast(NeighborhoodId neighborhoodId, Integer hours);

	/** 중기 예보 조회 — 일별 예보 (FR-WEA-03). */
	DailyForecast findDailyForecast(NeighborhoodId neighborhoodId);

	/**
	 * 체감온도 계산 (BR-WEA-12). 판정에 필요한 입력이 없으면 빈 값(기온으로 대체하지 않는다).
	 *
	 * @param date 여름 공식 적용 월 판정용
	 */
	Optional<Double> calculateFeelsLike(Double temperature, Double humidity, Double windSpeed, LocalDate date);

	/**
	 * 현장 제보 취소 (FR-WEA-14). 본인 제보만, 제보 후 weather.field-report.cancel-window 이내만. 이미 취소된 제보는 그대로 성공(멱등).
	 * 이미 발행된 {@link FieldReportPrecipitationReached}는 되돌리지 않는다.
	 *
	 * @throws kr.weathertalk.shared.error.DomainException NOT_FOUND(없음·본인 것 아님), FIELD_REPORT_CANCEL_WINDOW_PASSED
	 */
	FieldReportResult cancelFieldReport(MemberId memberId, UUID reportId);

	/** 현장 제보 현황 조회 (FR-WEA-05). @param requesterId 회원이면 본인 제보를 함께 준다. 비회원이면 null */
	FieldReportStatus findFieldReportStatus(NeighborhoodId neighborhoodId, MemberId requesterId);

	/** 예보 vs 주민 차이 조회 (FR-WEA-12). 판정은 weather만 한다. 제보 부족·기준 값 없음은 오류가 아니라 badge=false다. */
	ForecastDivergence findForecastDivergence(NeighborhoodId neighborhoodId);

}
