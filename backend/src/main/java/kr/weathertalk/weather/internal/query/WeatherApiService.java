package kr.weathertalk.weather.internal.query;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.FieldReportResult;
import kr.weathertalk.weather.FieldReportStatus;
import kr.weathertalk.weather.ForecastDivergence;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.WeatherApi;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.conversion.FeelsLikeCalculator;
import kr.weathertalk.weather.internal.fieldreport.FieldReportService;

/**
 * {@link WeatherApi} 구현 (FR-WEA-01~03, 05, 12~14, BR-WEA-12). 결과에는 동네 ID만 담는다(BR-WEA-21).
 */
@Service
class WeatherApiService implements WeatherApi {

	private final NeighborhoodApi neighborhoods;
	private final ObservationAssembler observations;
	private final ForecastAssembler forecasts;
	private final FeelsLikeCalculator feelsLike;
	private final FieldReportService fieldReports;
	private final DivergenceJudge divergence;
	private final WeatherProperties properties;
	private final Clock clock;

	WeatherApiService(NeighborhoodApi neighborhoods, ObservationAssembler observations, ForecastAssembler forecasts,
			FeelsLikeCalculator feelsLike, FieldReportService fieldReports, DivergenceJudge divergence,
			WeatherProperties properties, Clock clock) {
		this.fieldReports = fieldReports;
		this.divergence = divergence;
		this.neighborhoods = neighborhoods;
		this.observations = observations;
		this.forecasts = forecasts;
		this.feelsLike = feelsLike;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public CurrentObservation findCurrentObservation(NeighborhoodId neighborhoodId) {
		return observations.assemble(neighborhood(neighborhoodId), clock.instant(), true);
	}

	@Override
	public CurrentObservation findPastObservation(NeighborhoodId neighborhoodId, Instant at) {
		Instant now = clock.instant();
		if (at == null || at.isAfter(now)) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "과거 시각만 조회할 수 있습니다.");
		}
		return observations.assemble(neighborhood(neighborhoodId), at, false);
	}

	@Override
	public HourlyForecast findHourlyForecast(NeighborhoodId neighborhoodId, Integer hours) {
		int range = hours == null ? properties.shortTerm().defaultHours() : hours;
		if (range < 1) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "조회 범위 시간 수는 1 이상이어야 합니다.");
		}
		return forecasts.hourly(neighborhood(neighborhoodId), range, clock.instant());
	}

	@Override
	public DailyForecast findDailyForecast(NeighborhoodId neighborhoodId) {
		return forecasts.daily(neighborhood(neighborhoodId), clock.instant());
	}

	@Override
	public Optional<Double> calculateFeelsLike(Double temperature, Double humidity, Double windSpeed, LocalDate date) {
		return feelsLike.calculate(temperature, humidity, windSpeed, date == null ? null : date.getMonth());
	}

	@Override
	public FieldReportResult cancelFieldReport(MemberId memberId, UUID reportId) {
		return fieldReports.cancel(memberId, reportId);
	}

	@Override
	public FieldReportStatus findFieldReportStatus(NeighborhoodId neighborhoodId, MemberId requesterId) {
		return fieldReports.status(neighborhood(neighborhoodId).id(), requesterId);
	}

	@Override
	public ForecastDivergence findForecastDivergence(NeighborhoodId neighborhoodId) {
		return divergence.judge(neighborhood(neighborhoodId));
	}

	private Neighborhood neighborhood(NeighborhoodId id) {
		return neighborhoods.findNeighborhood(id)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "없는 동네입니다."));
	}

}
