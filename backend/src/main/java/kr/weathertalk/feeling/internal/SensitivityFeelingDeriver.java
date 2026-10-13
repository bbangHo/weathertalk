package kr.weathertalk.feeling.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.SensitivityFeeling;
import kr.weathertalk.feeling.SensitivityFeeling.TimeKind;
import kr.weathertalk.feeling.SensitivityFeelings;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.HourlyForecastSlot;
import kr.weathertalk.weather.WeatherApi;

/**
 * 민감도별 체감 산출 (FR-FEL-04, BR-FEL-13). 산출 순서:
 * <ol>
 * <li>동네 체감 — 현재·과거 시각이고 그룹의 온도 표본이 feeling.min-group-records 이상. 습도·바람은 각 범주 표본이 같은 기준 이상일 때만.</li>
 * <li>체감 매핑 — feeling.mapping.enabled이고 적용 중인 매핑 버전이 있을 때. 세부 → 계절 없음 → 축약 셀 순(FR-FEL-06).</li>
 * <li>고정 기준표 — 그 시각의 기온으로. 습도·바람은 담지 않는다.</li>
 * </ol>
 * 기상값: 현재 = 현재 관측, 미래 = 시간별 예보의 그 시각 칸, 과거 = 과거 관측. 미래는 1단계를 건너뛴다.
 * 동네 체감 집계 창의 끝은 현재면 지금(방금 남긴 기록까지 반영, BR-FEL-12), 과거면 요청 시각이다.
 */
@Component
class SensitivityFeelingDeriver {

	private record Basis(Instant basisTime, TimeKind kind, Instant windowEnd) {
	}

	private record WeatherValues(Double temperature, Double humidity, Double windSpeed) {

		static final WeatherValues NONE = new WeatherValues(null, null, null);

	}

	private final NeighborhoodFeelingAggregator aggregator;
	private final FeelingMappingLookup mapping;
	private final ReferenceTable referenceTable;
	private final WeatherApi weatherApi;
	private final FeelingProperties properties;
	private final Clock clock;

	SensitivityFeelingDeriver(NeighborhoodFeelingAggregator aggregator, FeelingMappingLookup mapping,
			ReferenceTable referenceTable, WeatherApi weatherApi, FeelingProperties properties, Clock clock) {
		this.aggregator = aggregator;
		this.mapping = mapping;
		this.referenceTable = referenceTable;
		this.weatherApi = weatherApi;
		this.properties = properties;
		this.clock = clock;
	}

	SensitivityFeelings deriveAll(NeighborhoodId neighborhoodId, Instant at) {
		return derive(neighborhoodId, List.of(TemperatureSensitivity.values()), at);
	}

	Optional<SensitivityFeeling> derive(NeighborhoodId neighborhoodId, TemperatureSensitivity sensitivity, Instant at) {
		return derive(neighborhoodId, List.of(sensitivity), at).find(sensitivity);
	}

	private SensitivityFeelings derive(NeighborhoodId neighborhoodId, List<TemperatureSensitivity> sensitivities,
			Instant at) {
		Instant now = clock.instant();
		Basis basis = basis(at == null ? now : at, now);
		NeighborhoodFeelingAggregator.Aggregation aggregation = basis.kind() == TimeKind.FUTURE ? null
				: aggregator.aggregate(neighborhoodId, basis.windowEnd());
		WeatherValues weather = weather(neighborhoodId, basis, now);
		List<SensitivityFeeling> results = new ArrayList<>();
		for (TemperatureSensitivity sensitivity : sensitivities) {
			derive(neighborhoodId, sensitivity, basis, aggregation, weather, now).ifPresent(results::add);
		}
		return new SensitivityFeelings(neighborhoodId, basis.basisTime(), List.copyOf(results));
	}

	private Optional<SensitivityFeeling> derive(NeighborhoodId neighborhoodId, TemperatureSensitivity sensitivity,
			Basis basis, NeighborhoodFeelingAggregator.Aggregation aggregation, WeatherValues weather, Instant now) {
		TemperatureTag weatherBased = weather.temperature() == null ? null
				: referenceTable.tagFor(sensitivity, weather.temperature());

		if (aggregation != null) {
			NeighborhoodFeelingAggregator.Group group = aggregation.groups().get(sensitivity);
			int min = properties.minGroupRecords();
			if (group.temperature().count() >= min && group.temperature().median() != null) {
				HumidityTag humidity = group.humidity().count() >= min && group.humidity().median() != null
						? HumidityTag.of(group.humidity().median()) : null;
				WindTag wind = group.wind().count() >= min && group.wind().median() != null
						? WindTag.of(group.wind().median()) : null;
				return Optional.of(new SensitivityFeeling(neighborhoodId, sensitivity, basis.basisTime(), basis.kind(),
						TemperatureTag.of(group.temperature().median()), humidity, wind,
						DerivationSource.NEIGHBORHOOD_FEELING, group.temperature().count(), null, null, weatherBased,
						now));
			}
		}

		Optional<FeelingMappingLookup.Cell> cell = mapping.find(sensitivity, basis.basisTime(), weather.temperature(),
				weather.humidity(), weather.windSpeed());
		if (cell.isPresent()) {
			FeelingMappingLookup.Cell c = cell.get();
			return Optional.of(new SensitivityFeeling(neighborhoodId, sensitivity, basis.basisTime(), basis.kind(),
					c.temperature(), c.humidity(), c.wind(), DerivationSource.FEELING_MAPPING, c.sampleCount(),
					c.mappingVersion(), c.layer(), weatherBased, now));
		}

		if (weatherBased == null) {
			return Optional.empty(); // 산출 불가
		}
		return Optional.of(new SensitivityFeeling(neighborhoodId, sensitivity, basis.basisTime(), basis.kind(),
				weatherBased, null, null, DerivationSource.REFERENCE_TABLE, null, null, null, weatherBased, now));
	}

	private Basis basis(Instant at, Instant now) {
		Instant basisTime = at.truncatedTo(ChronoUnit.HOURS);
		Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
		if (basisTime.equals(currentHour)) {
			return new Basis(basisTime, TimeKind.CURRENT, now);
		}
		if (basisTime.isAfter(currentHour)) {
			return new Basis(basisTime, TimeKind.FUTURE, at);
		}
		if (Duration.between(basisTime, currentHour).compareTo(properties.pastLookupLimit()) > 0) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "민감도별 체감은 과거 "
					+ properties.pastLookupLimit().toHours() + "시간까지만 조회할 수 있습니다.");
		}
		return new Basis(basisTime, TimeKind.PAST, at);
	}

	private WeatherValues weather(NeighborhoodId neighborhoodId, Basis basis, Instant now) {
		return switch (basis.kind()) {
			case CURRENT -> of(weatherApi.findCurrentObservation(neighborhoodId));
			case PAST -> of(weatherApi.findPastObservation(neighborhoodId, basis.windowEnd()));
			case FUTURE -> {
				int hours = (int) Duration.between(now.truncatedTo(ChronoUnit.HOURS), basis.basisTime()).toHours() + 1;
				HourlyForecast forecast = weatherApi.findHourlyForecast(neighborhoodId, hours);
				yield forecast.slots()
					.stream()
					.filter(slot -> slot.forecastAt().equals(basis.basisTime()))
					.findFirst()
					.map(SensitivityFeelingDeriver::of)
					.orElse(WeatherValues.NONE);
			}
		};
	}

	private static WeatherValues of(CurrentObservation observation) {
		return new WeatherValues(observation.temperature(), observation.humidity(), observation.windSpeed());
	}

	private static WeatherValues of(HourlyForecastSlot slot) {
		return new WeatherValues(slot.temperature(), slot.humidity(), slot.windSpeed());
	}

}
