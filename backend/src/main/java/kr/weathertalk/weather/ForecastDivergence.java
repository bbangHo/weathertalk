package kr.weathertalk.weather;

import java.time.Instant;
import java.util.List;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 예보 vs 주민 차이 조회 결과 (FR-WEA-12, BR-WEA-19). 판정은 weather만 하며 home은 표시만 한다. 저장하지 않고 조회마다 계산한다.
 *
 * @param axes 강수·하늘·대기 축마다 1건. 제보가 부족하거나 기준 측 값이 없으면 badge=false
 */
public record ForecastDivergence(NeighborhoodId neighborhoodId, Instant judgedAt, List<AxisDivergence> axes) {

	/**
	 * @param badge 배지 표시 여부. 강수 축 배지가 있으면 하늘 축은 false(강수 우선)
	 * @param basisValue 기준 측 값 식별자(강수 형태·하늘 상태·대기 등급 이름, 예: NONE·OVERCAST·GOOD). 없으면 null
	 * @param basisLabel 기준 측 값 표시문(예: "강수 없음", "흐림", "좋음")
	 * @param basisSource 기준 출처: 실황(OBSERVATION) / 예보(FORECAST) / 측정(MEASUREMENT)
	 * @param basisTime 근거 시각: 실황 관측 시각, 예보 발표 시각, 대기질 측정 시각
	 * @param residentKind 주민 다수 의견(BR-WEA-18). 성립하지 않으면 null
	 * @param reporterCount 그 축의 유효 제보자 수
	 * @param majorityCount 다수 종류 제보자 수
	 */
	public record AxisDivergence(DivergenceAxis axis, boolean badge, String basisValue, String basisLabel,
			ValueSource basisSource, Instant basisTime, FieldReportKind residentKind, int reporterCount,
			int majorityCount) {
	}

	public boolean hasBadge() {
		return axes.stream().anyMatch(AxisDivergence::badge);
	}

}
