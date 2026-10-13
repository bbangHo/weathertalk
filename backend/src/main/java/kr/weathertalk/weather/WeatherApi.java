package kr.weathertalk.weather;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * weather 모듈의 공개 인터페이스 (contracts.md §2.3). 다른 모듈은 이 타입으로만 weather 기능을 사용한다.
 * <p>
 * 조회는 외부 장애로 오류를 내지 않는다: 수집 실패면 마지막 정상값과 신선도 '지연'/'없음'으로 응답한다.
 * 현장 제보·예보 vs 주민 차이·강수 시작 판정은 M5에서 추가한다.
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

}
