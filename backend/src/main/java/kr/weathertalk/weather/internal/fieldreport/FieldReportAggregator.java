package kr.weathertalk.weather.internal.fieldreport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.weather.FieldReport;
import kr.weathertalk.weather.FieldReportAxis;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.FieldReportStatus;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.fieldreport.FieldReportRepository.StoredFieldReport;

/**
 * 현장 제보 현황 집계 (FR-WEA-05, BR-WEA-18). 유효 제보(valid-window 이내, 취소·대체 제외)에서 회원별·축별 최신 1건만 센다 —
 * 한 회원이 여러 번 눌러도 1명이다(03 §3.7 불변식). 저장하지 않고 조회마다 계산한다.
 */
@Component
public class FieldReportAggregator {

	/** 비율 경계(예: 3/5 = 0.6)가 부동소수 오차로 빠지지 않게 */
	private static final double SHARE_EPSILON = 1e-9;

	private final FieldReportRepository reports;
	private final WeatherProperties properties;
	private final Clock clock;

	FieldReportAggregator(FieldReportRepository reports, WeatherProperties properties, Clock clock) {
		this.reports = reports;
		this.properties = properties;
		this.clock = clock;
	}

	public FieldReportStatus status(NeighborhoodId neighborhoodId, MemberId requesterId) {
		Instant now = clock.instant();
		List<StoredFieldReport> active = reports.findActive(neighborhoodId.value(),
				now.minus(properties.fieldReport().validWindow()), now);
		List<FieldReportStatus.AxisStatus> axes = new ArrayList<>();
		List<FieldReport> mine = new ArrayList<>();
		for (FieldReportAxis axis : FieldReportAxis.values()) {
			List<StoredFieldReport> latest = latestPerReporter(active, axis);
			axes.add(axisStatus(axis, latest));
			if (requesterId != null) {
				latest.stream()
					.filter(r -> requesterId.value().equals(r.memberId()))
					.findFirst()
					.ifPresent(r -> mine.add(toView(r, properties)));
			}
		}
		return new FieldReportStatus(neighborhoodId, now, List.copyOf(axes), List.copyOf(mine));
	}

	/** 축의 제보를 제보자마다 가장 최근 1건으로 줄인다(최신순). */
	static List<StoredFieldReport> latestPerReporter(List<StoredFieldReport> reports, FieldReportAxis axis) {
		Map<Object, StoredFieldReport> latest = new LinkedHashMap<>();
		reports.stream()
			.filter(r -> r.axis() == axis)
			.sorted(Comparator.comparing(StoredFieldReport::reportedAt).reversed())
			.forEach(r -> latest.putIfAbsent(r.reporterKey(), r));
		return List.copyOf(latest.values());
	}

	private FieldReportStatus.AxisStatus axisStatus(FieldReportAxis axis, List<StoredFieldReport> latest) {
		Map<FieldReportKind, Integer> counts = new EnumMap<>(FieldReportKind.class);
		latest.forEach(r -> counts.merge(r.kind(), 1, Integer::sum));
		List<FieldReportStatus.KindCount> kindCounts = counts.entrySet()
			.stream()
			.sorted(Map.Entry.<FieldReportKind, Integer>comparingByValue().reversed())
			.map(e -> new FieldReportStatus.KindCount(e.getKey(), e.getValue()))
			.toList();
		FieldReportKind majority = majority(counts, latest.size(), properties.divergence().minReports(),
				properties.divergence().minShare());
		Instant latestAt = latest.isEmpty() ? null : latest.getFirst().reportedAt();
		return new FieldReportStatus.AxisStatus(axis, latest.size(), kindCounts, majority, latestAt);
	}

	/**
	 * BR-WEA-18 다수 의견: 제보자 수 ≥ minReports 그리고 한 종류의 비율 ≥ minShare일 때 그 종류. 동률로 둘 이상이 성립하면 없음.
	 */
	public static FieldReportKind majority(Map<FieldReportKind, Integer> counts, int reporters, int minReports,
			double minShare) {
		if (reporters <= 0 || reporters < minReports) {
			return null;
		}
		List<FieldReportKind> qualified = counts.entrySet()
			.stream()
			.filter(e -> (double) e.getValue() / reporters >= minShare - SHARE_EPSILON)
			.map(Map.Entry::getKey)
			.toList();
		return qualified.size() == 1 ? qualified.getFirst() : null;
	}

	static FieldReport toView(StoredFieldReport r, WeatherProperties properties) {
		return new FieldReport(r.id(), new NeighborhoodId(r.neighborhoodCode()), r.axis(), r.kind(), r.reportedAt(),
				r.reportedAt().plus(properties.fieldReport().cancelWindow()), r.canceledAt());
	}

}
