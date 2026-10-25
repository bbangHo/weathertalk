package kr.weathertalk.weather.internal.query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.AirGrade;
import kr.weathertalk.weather.AirQuality;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DayNight;
import kr.weathertalk.weather.Freshness;
import kr.weathertalk.weather.ObservationItem;
import kr.weathertalk.weather.PrecipitationAmount;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.UvIndex;
import kr.weathertalk.weather.ValueSource;
import kr.weathertalk.weather.internal.collect.CollectionKind;
import kr.weathertalk.weather.internal.collect.FreshnessRules;
import kr.weathertalk.weather.internal.collect.WeatherCollector;
import kr.weathertalk.weather.internal.conversion.DayNightJudge;
import kr.weathertalk.weather.internal.conversion.FeelsLikeCalculator;
import kr.weathertalk.weather.internal.conversion.RainPhraseCalculator;
import kr.weathertalk.weather.internal.region.AirStationMapper;
import kr.weathertalk.weather.internal.region.UvAreas;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.store.AirStore;
import kr.weathertalk.weather.internal.store.ObservationStore;
import kr.weathertalk.weather.internal.store.ShortTermStore;
import kr.weathertalk.weather.internal.store.UvStore;

/**
 * 현재 관측·과거 관측 조합 (FR-WEA-01·13, 03 §3.3).
 * <ul>
 * <li>실황이 정상·지연이면 실황 값(출처 실황). '없음'이면 같은 격자의 그 시각 예보 칸 값(출처 예보), 그것도 없으면 없음.</li>
 * <li>하늘 상태는 실황에 없으므로 그 시각을 덮는 예보 칸(출처 예보).</li>
 * <li>대기질: 가까운 측정소의 최신 측정. 측정이 결측이면 다음 측정소. 자외선: 밤이면 비운다.</li>
 * </ul>
 */
@Component
class ObservationAssembler {

	private final WeatherCollector collector;
	private final ObservationStore observations;
	private final ShortTermStore shortTerms;
	private final AirStore air;
	private final UvStore uv;
	private final AirStationMapper stationMapper;
	private final FreshnessRules freshness;
	private final FeelsLikeCalculator feelsLike;
	private final RainPhraseCalculator rainPhrase;
	private final DayNightJudge dayNight;

	ObservationAssembler(WeatherCollector collector, ObservationStore observations, ShortTermStore shortTerms,
			AirStore air, UvStore uv, AirStationMapper stationMapper, FreshnessRules freshness,
			FeelsLikeCalculator feelsLike, RainPhraseCalculator rainPhrase, DayNightJudge dayNight) {
		this.collector = collector;
		this.observations = observations;
		this.shortTerms = shortTerms;
		this.air = air;
		this.uv = uv;
		this.stationMapper = stationMapper;
		this.freshness = freshness;
		this.feelsLike = feelsLike;
		this.rainPhrase = rainPhrase;
		this.dayNight = dayNight;
	}

	/**
	 * @param collectIfStale 현재 관측이면 참(요청 시 수집). 과거 관측은 저장분만 쓴다(FR-WEA-13 3단계).
	 */
	CurrentObservation assemble(Neighborhood neighborhood, Instant at, boolean collectIfStale) {
		ForecastGrid grid = neighborhood.forecastGrid();
		if (collectIfStale) {
			collector.ensureObservation(grid, false);
			collector.ensureShortTerm(grid, false);
		}
		Instant hour = at.truncatedTo(ChronoUnit.HOURS);
		Optional<SourceData.Observation> observation = observations.findLatestAtOrBefore(grid, at);
		Freshness weatherFreshness = freshness.observation(observation.map(SourceData.Observation::observedAt).orElse(null),
				at);
		SourceData.ShortTermSlot slot = forecastSlot(grid, hour, at);

		Map<ObservationItem, ValueSource> sources = new EnumMap<>(ObservationItem.class);
		Instant observedAt = null;
		Double temperature = null;
		Double humidity = null;
		Double windSpeed = null;
		PrecipitationAmount precipitation = null;
		PrecipitationType precipitationType = null;
		PrecipitationAmount snowfall = null;
		if (weatherFreshness != Freshness.NONE) {
			SourceData.Observation o = observation.get();
			observedAt = o.observedAt();
			temperature = put(sources, ObservationItem.TEMPERATURE, o.temperature(), ValueSource.OBSERVATION);
			humidity = put(sources, ObservationItem.HUMIDITY, o.humidity(), ValueSource.OBSERVATION);
			windSpeed = put(sources, ObservationItem.WIND_SPEED, o.windSpeed(), ValueSource.OBSERVATION);
			precipitation = put(sources, ObservationItem.PRECIPITATION, o.precipitation(), ValueSource.OBSERVATION);
			precipitationType = put(sources, ObservationItem.PRECIPITATION_TYPE, o.precipitationType(),
					ValueSource.OBSERVATION);
		}
		else if (slot != null) {
			// '없음'인 실황은 현재로 보여주지 않고 같은 시각 예보 칸으로 채운다(FR-WEA-01 5단계)
			observedAt = slot.forecastAt();
			temperature = put(sources, ObservationItem.TEMPERATURE, slot.temperature(), ValueSource.FORECAST);
			humidity = put(sources, ObservationItem.HUMIDITY, slot.humidity(), ValueSource.FORECAST);
			windSpeed = put(sources, ObservationItem.WIND_SPEED, slot.windSpeed(), ValueSource.FORECAST);
			precipitation = put(sources, ObservationItem.PRECIPITATION, slot.precipitation(), ValueSource.FORECAST);
			precipitationType = put(sources, ObservationItem.PRECIPITATION_TYPE, slot.precipitationType(),
					ValueSource.FORECAST);
			snowfall = slot.snowfall();
		}
		var sky = slot == null ? null : put(sources, ObservationItem.SKY, slot.sky(), ValueSource.FORECAST);
		Instant basis = observedAt != null ? observedAt : at;
		Double feels = feelsLike
			.calculate(temperature, humidity, windSpeed, basis.atZone(TimeConfig.SEOUL).getMonth())
			.orElse(null);
		if (feels != null) {
			sources.put(ObservationItem.FEELS_LIKE, sources.get(ObservationItem.TEMPERATURE));
		}
		DayNight dn = dayNight.at(at);
		AirQuality airQuality = airQuality(neighborhood, at, collectIfStale);
		if (airQuality.hasValue()) {
			sources.put(ObservationItem.AIR_QUALITY, ValueSource.MEASUREMENT);
		}
		UvIndex uvIndex = dn == DayNight.NIGHT ? null : uvIndex(neighborhood, at, collectIfStale);
		if (uvIndex != null && uvIndex.value() != null) {
			sources.put(ObservationItem.UV, ValueSource.MEASUREMENT);
		}
		return new CurrentObservation(neighborhood.id(), observedAt, temperature, humidity, windSpeed, precipitation,
				precipitationType, rainPhrase.phrase(precipitationType, precipitation, snowfall), sky, feels,
				Collections.unmodifiableMap(sources), weatherFreshness, airQuality, uvIndex, dn);
	}

	/**
	 * 그 시각 이전 발표분 중 쓸 수 있는(신선도가 '없음'이 아닌) 최신 발표분에서 그 시각 칸.
	 * 발표분은 발표 시각 다음 시부터 칸이 있어 발표 직후 한 시간은 그 시각 칸이 없다. 그때는 직전 발표분의 칸을 쓴다.
	 */
	private SourceData.ShortTermSlot forecastSlot(ForecastGrid grid, Instant hour, Instant at) {
		Optional<ShortTermStore.Issue> issue = shortTerms.findLatestIssuedAtOrBefore(grid, at);
		if (issue.isEmpty()) {
			return null;
		}
		List<SourceData.ShortTermSlot> slots = issue.get().slots();
		boolean remaining = slots.stream().anyMatch(s -> !s.forecastAt().isBefore(hour));
		if (freshness.shortTerm(issue.get().issuedAt(), at, remaining) == Freshness.NONE) {
			return null;
		}
		return slots.stream()
			.filter(s -> s.forecastAt().equals(hour))
			.findFirst()
			.or(() -> shortTerms.findSlot(grid, hour, issue.get().issuedAt()))
			.orElse(null);
	}

	private AirQuality airQuality(Neighborhood neighborhood, Instant at, boolean collectIfStale) {
		List<String> stations = stationMapper.stations(neighborhood, collectIfStale);
		if (collectIfStale && !stations.isEmpty()) {
			collector.ensureAir(false);
		}
		for (String station : stations) {
			Optional<SourceData.AirMeasurement> latest = air.findLatestAtOrBefore(station, at);
			if (latest.isEmpty() || (latest.get().pm10() == null && latest.get().pm25() == null
					&& latest.get().pm10Grade() == null && latest.get().pm25Grade() == null)) {
				continue; // 가장 가까운 측정소의 최신 측정이 결측이면 다음 측정소(03 §3.6)
			}
			SourceData.AirMeasurement m = latest.get();
			Freshness airFreshness = freshness.air(m.measuredAt(), at);
			if (airFreshness == Freshness.NONE) {
				return AirQuality.none();
			}
			return new AirQuality(station, m.measuredAt(), m.pm10(), m.pm25(), m.pm10Grade(), m.pm25Grade(),
					AirGrade.worse(m.pm10Grade(), m.pm25Grade()), m.ozoneGrade(), airFreshness);
		}
		return AirQuality.none();
	}

	private UvIndex uvIndex(Neighborhood neighborhood, Instant at, boolean collectIfStale) {
		String area = UvAreas.areaNo(neighborhood.id());
		if (collectIfStale) {
			collector.ensureUv(area, false);
		}
		Optional<UvStore.UvIssue> issue = uv.findLatestIssuedAtOrBefore(area, at);
		if (issue.isEmpty()) {
			return UvIndex.none();
		}
		Freshness uvFreshness = freshness.byIssue(CollectionKind.UV, issue.get().issuedAt(), at);
		if (uvFreshness == Freshness.NONE) {
			return UvIndex.none();
		}
		Integer value = issue.get()
			.values()
			.stream()
			.filter(v -> !v.forecastAt().isAfter(at))
			.max(Comparator.comparing(SourceData.UvValue::forecastAt))
			.map(SourceData.UvValue::value)
			.orElse(null);
		return UvIndex.of(issue.get().issuedAt(), value, uvFreshness);
	}

	private static <T> T put(Map<ObservationItem, ValueSource> sources, ObservationItem item, T value,
			ValueSource source) {
		if (value != null) {
			sources.put(item, source);
		}
		return value;
	}

}
