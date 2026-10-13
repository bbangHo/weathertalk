package kr.weathertalk.feeling;

import java.util.Map;

/**
 * 동네 체감의 한 범주·한 대상(그룹 또는 전체) 집계 (04 §3.4, BR-FEL-09~11). 범주는 서로 독립이며 비어 있어도 실패가 아니다.
 *
 * @param sampleCount 가중 전 건수
 * @param distribution 단계별 건수(가중 전). 모든 단계를 단계 순서로 담는다(0건 포함)
 * @param representative 대표 단계(가중 중앙값). 표본이 feeling.neighborhood.min-display-records 미만이면 null
 */
public record CategorySummary<T extends FeelingTag>(int sampleCount, Map<T, Integer> distribution, T representative,
		Status status) {

	public enum Status {

		OK,

		/** 표본은 있으나 대표 단계를 보여줄 만큼은 아님 */
		INSUFFICIENT_RECORDS,

		/** 기록 없음 */
		NO_RECORDS

	}

}
