package kr.weathertalk.weather.internal.query;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DivergenceAxis;
import kr.weathertalk.weather.FieldReportAxis;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.FieldReportStatus;
import kr.weathertalk.weather.ForecastDivergence;
import kr.weathertalk.weather.ForecastDivergence.AxisDivergence;
import kr.weathertalk.weather.ObservationItem;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;
import kr.weathertalk.weather.ValueSource;
import kr.weathertalk.weather.internal.fieldreport.FieldReportAggregator;
import kr.weathertalk.weather.internal.store.ShortTermStore;

/**
 * 예보 vs 주민 차이 판정 (FR-WEA-12, BR-WEA-18·19). 판정은 weather만 한다.
 * <ul>
 * <li>기준 측 값은 현재 관측 조회와 같은 사본: 강수 = 실황(정상·지연) 강수 형태, 실황 '없음'이면 현재 시 예보 칸. 하늘 = 현재 시 예보 칸.
 * 대기 = 통합 미세먼지 등급(정상·지연).</li>
 * <li>주민 측 값은 축별 다수 의견(BR-WEA-18). 성립하지 않거나 기준 측 값이 없으면 그 축은 표시 안 함.</li>
 * <li>구름많음은 맑음·흐림 어느 쪽과도 어긋남으로 보지 않는다. 비 ↔ 눈 형태 차이는 배지 대상이 아니다. 강수 배지가 있으면 하늘 배지는 내지 않는다.</li>
 * </ul>
 */
@Component
class DivergenceJudge {

	private final ObservationAssembler observations;
	private final FieldReportAggregator reports;
	private final ShortTermStore shortTerms;
	private final Clock clock;

	DivergenceJudge(ObservationAssembler observations, FieldReportAggregator reports, ShortTermStore shortTerms,
			Clock clock) {
		this.observations = observations;
		this.reports = reports;
		this.shortTerms = shortTerms;
		this.clock = clock;
	}

	ForecastDivergence judge(Neighborhood neighborhood) {
		Instant now = clock.instant();
		FieldReportStatus status = reports.status(neighborhood.id(), null);
		CurrentObservation current = observations.assemble(neighborhood, now, true);
		// 예보 출처 값의 근거 시각은 그 칸이 든 발표분의 발표 시각
		Instant forecastIssuedAt = shortTerms.findLatestIssuedAtOrBefore(neighborhood.forecastGrid(), now)
			.map(ShortTermStore.Issue::issuedAt)
			.orElse(null);
		FieldReportStatus.AxisStatus sky = status.axis(FieldReportAxis.SKY_PRECIPITATION).orElseThrow();
		FieldReportStatus.AxisStatus air = status.axis(FieldReportAxis.AIR).orElseThrow();

		AxisDivergence precipitation = precipitation(current, sky, forecastIssuedAt);
		AxisDivergence skyAxis = sky(current, sky, forecastIssuedAt, precipitation.badge());
		AxisDivergence airAxis = air(current, air);
		return new ForecastDivergence(neighborhood.id(), now, List.of(precipitation, skyAxis, airAxis));
	}

	private static AxisDivergence precipitation(CurrentObservation current, FieldReportStatus.AxisStatus residents,
			Instant forecastIssuedAt) {
		PrecipitationType basis = current.precipitationType();
		ValueSource source = current.sources().get(ObservationItem.PRECIPITATION_TYPE);
		Instant basisTime = source == ValueSource.FORECAST ? forecastIssuedAt : current.observedAt();
		FieldReportKind majority = residents.majorityKind();
		boolean badge = false;
		if (basis != null && majority != null) {
			boolean residentsSayPrecipitating = majority.precipitationType() != null;
			badge = residentsSayPrecipitating != basis.isPrecipitating();
		}
		return new AxisDivergence(DivergenceAxis.PRECIPITATION, badge, basis == null ? null : basis.name(),
				basis == null ? null : basis.label(), basis == null ? null : source, basis == null ? null : basisTime,
				majority, residents.reporterCount(), majorityCount(residents));
	}

	private static AxisDivergence sky(CurrentObservation current, FieldReportStatus.AxisStatus residents,
			Instant forecastIssuedAt, boolean precipitationBadge) {
		SkyCondition basis = current.sky();
		FieldReportKind majority = residents.majorityKind();
		boolean badge = false;
		if (basis != null && majority != null && !precipitationBadge) {
			badge = (majority == FieldReportKind.CLEAR && basis == SkyCondition.OVERCAST)
					|| (majority == FieldReportKind.OVERCAST && basis == SkyCondition.CLEAR);
		}
		return new AxisDivergence(DivergenceAxis.SKY, badge, basis == null ? null : basis.name(),
				basis == null ? null : basis.label(), basis == null ? null : ValueSource.FORECAST,
				basis == null ? null : forecastIssuedAt, majority, residents.reporterCount(),
				majorityCount(residents));
	}

	private static AxisDivergence air(CurrentObservation current, FieldReportStatus.AxisStatus residents) {
		AirGrade basis = current.airQuality() == null ? null : current.airQuality().combinedGrade();
		FieldReportKind majority = residents.majorityKind();
		boolean badge = false;
		if (basis != null && majority != null) {
			boolean bad = basis == AirGrade.BAD || basis == AirGrade.VERY_BAD;
			badge = (majority == FieldReportKind.DUST_BAD && !bad) || (majority == FieldReportKind.AIR_CLEAN && bad);
		}
		return new AxisDivergence(DivergenceAxis.AIR, badge, basis == null ? null : basis.name(),
				basis == null ? null : basis.label(), basis == null ? null : ValueSource.MEASUREMENT,
				basis == null ? null : current.airQuality().measuredAt(), majority, residents.reporterCount(),
				majorityCount(residents));
	}

	private static int majorityCount(FieldReportStatus.AxisStatus residents) {
		if (residents.majorityKind() == null) {
			return 0;
		}
		return residents.counts()
			.stream()
			.filter(c -> c.kind() == residents.majorityKind())
			.mapToInt(FieldReportStatus.KindCount::reporterCount)
			.sum();
	}

}
