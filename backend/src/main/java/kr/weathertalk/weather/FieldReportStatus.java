package kr.weathertalk.weather;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 현장 제보 현황 조회 결과 (FR-WEA-05). 유효 제보(weather.field-report.valid-window 이내, 취소·대체 제외)의 회원별·축별 최신 1건만 센다.
 * 제보자 회원 ID·닉네임은 담지 않는다. 차이 배지는 여기 담지 않는다({@link ForecastDivergence}).
 *
 * @param axes 축마다 1건(제보가 없어도 0명으로 담는다)
 * @param myReports 요청자가 회원이면 축별 본인 유효 제보(제보 ID·취소 가능 기한 포함), 아니면 빈 목록
 */
public record FieldReportStatus(NeighborhoodId neighborhoodId, Instant aggregatedAt, List<AxisStatus> axes,
		List<FieldReport> myReports) {

	/**
	 * @param counts 종류별 제보자 수(0명 종류 제외, 많은 순)
	 * @param majorityKind 다수 의견(BR-WEA-18: 제보자 수 ≥ weather.divergence.min-reports 그리고 비율 ≥ min-share). 없으면 null
	 * @param latestReportedAt 가장 최근 제보 시각. 제보가 없으면 null
	 */
	public record AxisStatus(FieldReportAxis axis, int reporterCount, List<KindCount> counts,
			FieldReportKind majorityKind, Instant latestReportedAt) {
	}

	public record KindCount(FieldReportKind kind, int reporterCount) {
	}

	public Optional<AxisStatus> axis(FieldReportAxis axis) {
		return axes.stream().filter(a -> a.axis() == axis).findFirst();
	}

	public boolean isEmpty() {
		return axes.stream().allMatch(a -> a.reporterCount() == 0) && myReports.isEmpty();
	}

}
