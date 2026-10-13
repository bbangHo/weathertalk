package kr.weathertalk.weather.internal.query;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.DailyForecastDay;
import kr.weathertalk.weather.DailyForecastSource;
import kr.weathertalk.weather.Freshness;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.HourlyForecastSlot;
import kr.weathertalk.weather.NextPrecipitation;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;
import kr.weathertalk.weather.internal.collect.CollectionKind;
import kr.weathertalk.weather.internal.collect.FreshnessRules;
import kr.weathertalk.weather.internal.collect.WeatherCollector;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.conversion.DayNightJudge;
import kr.weathertalk.weather.internal.conversion.FeelsLikeCalculator;
import kr.weathertalk.weather.internal.conversion.RainPhraseCalculator;
import kr.weathertalk.weather.internal.region.MidTermRegions;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.store.MidTermStore;
import kr.weathertalk.weather.internal.store.ShortTermStore;

/**
 * 시간별 예보(FR-WEA-02)·일별 예보(FR-WEA-03, 03 §3.5) 조합.
 */
@Component
class ForecastAssembler {

	/** 일별 예보 오전·오후 대표 칸과 구분 시(時). 표시 규칙이라 운영 파라미터로 두지 않는다(03 구현 메모). */
	private static final int NOON = 12;
	private static final int AM_REPRESENTATIVE_HOUR = 9;
	private static final int PM_REPRESENTATIVE_HOUR = 15;
	private static final int LAST_HOUR = 23;

	private final WeatherCollector collector;
	private final ShortTermStore shortTerms;
	private final MidTermStore midTerms;
	private final MidTermRegions regions;
	private final FreshnessRules freshness;
	private final FeelsLikeCalculator feelsLike;
	private final RainPhraseCalculator rainPhrase;
	private final DayNightJudge dayNight;
	private final WeatherProperties properties;

	ForecastAssembler(WeatherCollector collector, ShortTermStore shortTerms, MidTermStore midTerms,
			MidTermRegions regions, FreshnessRules freshness, FeelsLikeCalculator feelsLike,
			RainPhraseCalculator rainPhrase, DayNightJudge dayNight, WeatherProperties properties) {
		this.collector = collector;
		this.shortTerms = shortTerms;
		this.midTerms = midTerms;
		this.regions = regions;
		this.freshness = freshness;
		this.feelsLike = feelsLike;
		this.rainPhrase = rainPhrase;
		this.dayNight = dayNight;
		this.properties = properties;
	}

	private record UsableShortTerm(Instant issuedAt, Freshness freshness, List<SourceData.ShortTermSlot> remaining) {
	}

	/** 현재 시 정각 이후 칸만. 신선도 '없음'이면 빈 목록(FR-WEA-02 실패 케이스). */
	private UsableShortTerm usableShortTerm(ForecastGrid grid, Instant now) {
		collector.ensureShortTerm(grid, false);
		Instant hour = now.truncatedTo(ChronoUnit.HOURS);
		Optional<ShortTermStore.Issue> issue = shortTerms.findLatestIssuedAtOrBefore(grid, now);
		if (issue.isEmpty()) {
			return new UsableShortTerm(null, Freshness.NONE, List.of());
		}
		List<SourceData.ShortTermSlot> remaining = issue.get()
			.slots()
			.stream()
			.filter(s -> !s.forecastAt().isBefore(hour))
			.sorted(Comparator.comparing(SourceData.ShortTermSlot::forecastAt))
			.toList();
		Freshness f = freshness.shortTerm(issue.get().issuedAt(), now, !remaining.isEmpty());
		if (f == Freshness.NONE) {
			return new UsableShortTerm(null, Freshness.NONE, List.of());
		}
		return new UsableShortTerm(issue.get().issuedAt(), f, remaining);
	}

	HourlyForecast hourly(Neighborhood neighborhood, int hours, Instant now) {
		UsableShortTerm shortTerm = usableShortTerm(neighborhood.forecastGrid(), now);
		Instant hour = now.truncatedTo(ChronoUnit.HOURS);
		Instant until = hour.plus(Duration.ofHours(hours));
		List<HourlyForecastSlot> slots = shortTerm.remaining()
			.stream()
			.filter(s -> s.forecastAt().isBefore(until))
			.map(this::toSlot)
			.toList();
		Instant horizon = hour.plus(properties.nextPrecip().horizon());
		// 강수 형태가 있는 가장 이른 칸(강수확률만 높은 칸은 제외, BR-WEA-14, legacy 04-#17)
		NextPrecipitation next = shortTerm.remaining()
			.stream()
			.filter(s -> s.forecastAt().isBefore(horizon))
			.filter(s -> s.precipitationType() != null && s.precipitationType().isPrecipitating())
			.findFirst()
			.map(s -> new NextPrecipitation(s.forecastAt(), s.precipitationType(),
					rainPhrase.phrase(s.precipitationType(), s.precipitation(), s.snowfall())))
			.orElse(null);
		return new HourlyForecast(neighborhood.id(), shortTerm.issuedAt(), shortTerm.freshness(), slots, next);
	}

	private HourlyForecastSlot toSlot(SourceData.ShortTermSlot s) {
		ZonedDateTime at = s.forecastAt().atZone(TimeConfig.SEOUL);
		return new HourlyForecastSlot(s.forecastAt(), at.toLocalDate(), at.getHour(), s.temperature(),
				feelsLike.calculate(s.temperature(), s.humidity(), s.windSpeed(), at.getMonth()).orElse(null),
				s.humidity(), s.windSpeed(), s.precipitationProbability(), s.precipitationType(), s.precipitation(),
				s.snowfall(), rainPhrase.phrase(s.precipitationType(), s.precipitation(), s.snowfall()), s.sky(),
				dayNight.at(s.forecastAt()));
	}

	DailyForecast daily(Neighborhood neighborhood, Instant now) {
		LocalDate today = now.atZone(TimeConfig.SEOUL).toLocalDate();
		UsableShortTerm shortTerm = usableShortTerm(neighborhood.forecastGrid(), now);
		Map<LocalDate, List<SourceData.ShortTermSlot>> shortByDate = shortTerm.remaining()
			.stream()
			.collect(Collectors.groupingBy(s -> s.forecastAt().atZone(TimeConfig.SEOUL).toLocalDate(), TreeMap::new,
					Collectors.toList()));

		Optional<MidTermRegions.MidTermRegion> region = regions.find(neighborhood);
		MidPart land = region.map(r -> midLand(r.landRegionId(), now)).orElse(MidPart.NONE);
		MidPart temperature = region.map(r -> midTemperature(r.temperatureRegionId(), now)).orElse(MidPart.NONE);
		Map<LocalDate, SourceData.MidLandDay> landDays = land.landDays();
		Map<LocalDate, SourceData.MidTemperatureDay> temperatureDays = temperature.temperatureDays();

		List<DailyForecastDay> days = new ArrayList<>();
		Freshness overall = null;
		for (int i = 0; i < properties.midTermDays(); i++) {
			LocalDate date = today.plusDays(i);
			List<SourceData.ShortTermSlot> slots = shortByDate.getOrDefault(date, List.of());
			if (coversRestOfDay(slots)) {
				days.add(fromShortTerm(date, slots, shortTerm.issuedAt()));
				overall = overall == null ? shortTerm.freshness() : overall.worse(shortTerm.freshness());
			}
			else if (landDays.containsKey(date) || temperatureDays.containsKey(date)) {
				SourceData.MidLandDay l = landDays.get(date);
				SourceData.MidTemperatureDay t = temperatureDays.get(date);
				days.add(new DailyForecastDay(date, t == null ? null : t.min(), t == null ? null : t.max(),
						l == null ? null : l.amSky(), l == null ? null : l.pmSky(),
						l == null ? null : l.amPrecipitationProbability(),
						l == null ? null : l.pmPrecipitationProbability(), l == null ? null : l.amPrecipitationType(),
						l == null ? null : l.pmPrecipitationType(), DailyForecastSource.MID_TERM,
						l != null ? land.issuedAt() : temperature.issuedAt()));
				Freshness midFreshness = l != null && t != null ? land.freshness().worse(temperature.freshness())
						: l != null ? land.freshness() : temperature.freshness();
				overall = overall == null ? midFreshness : overall.worse(midFreshness);
			}
			else {
				// 어느 원천도 덮지 않는 날은 비워 둔다(다른 날 값으로 채우지 않음)
				days.add(DailyForecastDay.empty(date));
			}
		}
		return new DailyForecast(neighborhood.id(), overall == null ? Freshness.NONE : overall, List.copyOf(days));
	}

	/** 그 날짜의 남은 시간을 끝(23시)까지 덮는가. 오늘은 지난 칸이 빠지므로 '남은 시간'을 기준으로 본다(03 구현 메모). */
	private static boolean coversRestOfDay(List<SourceData.ShortTermSlot> slots) {
		return !slots.isEmpty()
				&& slots.get(slots.size() - 1).forecastAt().atZone(TimeConfig.SEOUL).getHour() == LAST_HOUR;
	}

	/** 최고·최저는 TMX·TMN, 없으면 그 날 칸들의 최대·최소(03 §3.5). 화면은 이 하나의 정의만 쓴다(legacy 04-#27). */
	private static DailyForecastDay fromShortTerm(LocalDate date, List<SourceData.ShortTermSlot> slots,
			Instant issuedAt) {
		Double max = slots.stream().map(SourceData.ShortTermSlot::dailyMax).filter(Objects::nonNull).findFirst()
			.orElse(slots.stream().map(SourceData.ShortTermSlot::temperature).filter(Objects::nonNull)
				.max(Double::compare).orElse(null));
		Double min = slots.stream().map(SourceData.ShortTermSlot::dailyMin).filter(Objects::nonNull).findFirst()
			.orElse(slots.stream().map(SourceData.ShortTermSlot::temperature).filter(Objects::nonNull)
				.min(Double::compare).orElse(null));
		List<SourceData.ShortTermSlot> am = slots.stream().filter(s -> hourOf(s) < NOON).toList();
		List<SourceData.ShortTermSlot> pm = slots.stream().filter(s -> hourOf(s) >= NOON).toList();
		return new DailyForecastDay(date, min, max, sky(am, AM_REPRESENTATIVE_HOUR), sky(pm, PM_REPRESENTATIVE_HOUR),
				maxPop(am), maxPop(pm), type(am), type(pm), DailyForecastSource.SHORT_TERM, issuedAt);
	}

	private static int hourOf(SourceData.ShortTermSlot slot) {
		return slot.forecastAt().atZone(TimeConfig.SEOUL).getHour();
	}

	private static SkyCondition sky(List<SourceData.ShortTermSlot> half, int representativeHour) {
		return half.stream()
			.filter(s -> hourOf(s) == representativeHour)
			.findFirst()
			.or(() -> half.stream().findFirst())
			.map(SourceData.ShortTermSlot::sky)
			.orElse(null);
	}

	private static Integer maxPop(List<SourceData.ShortTermSlot> half) {
		return half.stream()
			.map(SourceData.ShortTermSlot::precipitationProbability)
			.filter(Objects::nonNull)
			.max(Integer::compare)
			.orElse(null);
	}

	private static PrecipitationType type(List<SourceData.ShortTermSlot> half) {
		List<PrecipitationType> known = half.stream()
			.map(SourceData.ShortTermSlot::precipitationType)
			.filter(Objects::nonNull)
			.toList();
		if (known.isEmpty()) {
			return null;
		}
		return known.stream().filter(PrecipitationType::isPrecipitating).findFirst().orElse(PrecipitationType.NONE);
	}

	private record MidPart(Instant issuedAt, Freshness freshness, Map<LocalDate, SourceData.MidLandDay> landDays,
			Map<LocalDate, SourceData.MidTemperatureDay> temperatureDays) {

		static final MidPart NONE = new MidPart(null, Freshness.NONE, Map.of(), Map.of());

	}

	/** 중기는 전국 정기 수집분만 쓰고, 그게 '없음'일 때만 요청 시 1회 수집한다(FR-WEA-03). */
	private MidPart midLand(String regionId, Instant now) {
		Optional<MidTermStore.LandIssue> issue = midTerms.findLatestLand(regionId);
		if (freshness.byIssue(CollectionKind.MID_LAND, issue.map(MidTermStore.LandIssue::issuedAt).orElse(null),
				now) == Freshness.NONE) {
			collector.ensureMidLand(regionId, false);
			issue = midTerms.findLatestLand(regionId);
		}
		if (issue.isEmpty()) {
			return MidPart.NONE;
		}
		Freshness f = freshness.byIssue(CollectionKind.MID_LAND, issue.get().issuedAt(), now);
		if (f == Freshness.NONE) {
			return MidPart.NONE;
		}
		return new MidPart(issue.get().issuedAt(), f, issue.get()
			.days()
			.stream()
			.collect(Collectors.toMap(SourceData.MidLandDay::date, Function.identity())), Map.of());
	}

	private MidPart midTemperature(String regionId, Instant now) {
		Optional<MidTermStore.TemperatureIssue> issue = midTerms.findLatestTemperature(regionId);
		if (freshness.byIssue(CollectionKind.MID_TEMPERATURE,
				issue.map(MidTermStore.TemperatureIssue::issuedAt).orElse(null), now) == Freshness.NONE) {
			collector.ensureMidTemperature(regionId, false);
			issue = midTerms.findLatestTemperature(regionId);
		}
		if (issue.isEmpty()) {
			return MidPart.NONE;
		}
		Freshness f = freshness.byIssue(CollectionKind.MID_TEMPERATURE, issue.get().issuedAt(), now);
		if (f == Freshness.NONE) {
			return MidPart.NONE;
		}
		return new MidPart(issue.get().issuedAt(), f, Map.of(), issue.get()
			.days()
			.stream()
			.collect(Collectors.toMap(SourceData.MidTemperatureDay::date, Function.identity())));
	}

}
