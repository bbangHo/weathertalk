package kr.weathertalk.notification.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.SensitivityFeeling;
import kr.weathertalk.feeling.SensitivityFeelings;
import kr.weathertalk.feeling.YesterdayComparison;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.internal.DeliveryStore.SkipReason;
import kr.weathertalk.notification.internal.Dispatcher.Candidate;
import kr.weathertalk.notification.internal.Dispatcher.QuietPolicy;
import kr.weathertalk.notification.internal.Dispatcher.Round;
import kr.weathertalk.notification.internal.Dispatcher.SkippedTarget;
import kr.weathertalk.notification.internal.MessageTemplates.DailyContent;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.outfit.OutfitApi;
import kr.weathertalk.outfit.Precipitation;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.DailyForecastDay;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.HourlyForecastSlot;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.WeatherApi;

/**
 * 정기 날씨 알림 (FR-NOTI-09, ADR 0013).
 * <ul>
 * <li>notification.daily.time-unit마다(Asia/Seoul 자정 기준 정렬) 발송 회차 하나를 연다. 회차는 noti_daily_round에 한 번만 열리고(분산 락과 별개),
 * 발송 키 유일성이 최종 중복 방지다(BR-NOTI-10).</li>
 * <li>회차 시각보다 notification.daily.max-delay 넘게 늦게 열리면 발송하지 않고 '생략(지연 초과)'로 남긴다(아침 7시 알림이 10시에 가지 않게).</li>
 * <li>(동네, 온도 민감도) 조합마다 한 번만 계산해 회차 안에서 재사용한다. 핵심 값(현재 관측·시간별 예보)이 둘 다 없으면 그 동네는
 * '생략(날씨 없음)', 부가 값(체감·옷 추천·어제 비교)이 없으면 그 줄만 뺀다. 한 동네의 실패가 다른 동네를 막지 않는다(legacy 05-#5, 04-#24).</li>
 * <li>심야 금지 시간·쿨다운을 적용하지 않는다(구독자가 고른 시각, BR-NOTI-07·08).</li>
 * <li>회차 prepare-ahead 전에 대상 동네의 날씨 조회를 미리 불러 데워 둔다(명령이 아니라 조회, 신선도 판단은 weather, ADR 0005).</li>
 * </ul>
 */
@Service
public class DailyWeatherAlerts {

	private static final Logger log = LoggerFactory.getLogger(DailyWeatherAlerts.class);
	private static final String UNRESOLVED = "-";

	private final Subscribers subscribers;
	private final Dispatcher dispatcher;
	private final MessageTemplates templates;
	private final NeighborhoodApi neighborhoodApi;
	private final WeatherApi weatherApi;
	private final FeelingApi feelingApi;
	private final OutfitApi outfitApi;
	private final NotificationProperties properties;
	private final JdbcClient jdbc;
	private final Clock clock;
	private final Set<Instant> warmed = Collections.synchronizedSet(new LinkedHashSet<>());

	DailyWeatherAlerts(Subscribers subscribers, Dispatcher dispatcher, MessageTemplates templates,
			NeighborhoodApi neighborhoodApi, WeatherApi weatherApi, FeelingApi feelingApi, OutfitApi outfitApi,
			NotificationProperties properties, JdbcClient jdbc, Clock clock) {
		this.subscribers = subscribers;
		this.dispatcher = dispatcher;
		this.templates = templates;
		this.neighborhoodApi = neighborhoodApi;
		this.weatherApi = weatherApi;
		this.feelingApi = feelingApi;
		this.outfitApi = outfitApi;
		this.properties = properties;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** 정기 확인(1분마다): 아직 열리지 않은 최근 회차를 열고, 다가오는 회차를 예열한다. */
	public void tick() {
		Instant now = clock.instant();
		Duration unit = properties.daily().timeUnit();
		Duration maxDelay = properties.daily().maxDelay();
		ZonedDateTime current = floor(now.atZone(TimeConfig.SEOUL), unit);
		long lookback = maxDelay.plus(unit.multipliedBy(2)).toSeconds() / unit.toSeconds();
		for (long k = lookback; k >= 0; k--) {
			ZonedDateTime round = current.minus(unit.multipliedBy(k));
			if (round.toInstant().isAfter(now)) {
				continue;
			}
			runRound(round);
		}
		ZonedDateTime next = current.plus(unit);
		if (!now.isBefore(next.toInstant().minus(properties.daily().prepareAhead()))
				&& warmed.add(next.toInstant())) {
			warmUp(next);
			if (warmed.size() > 100) {
				warmed.remove(warmed.iterator().next());
			}
		}
	}

	/**
	 * 회차 하나를 연다. 이미 열린 회차면 아무것도 하지 않는다.
	 *
	 * @return 이번에 열었으면 발송 결과, 이미 열렸으면 빈 값
	 */
	public Optional<Dispatcher.Report> runRound(ZonedDateTime round) {
		Instant now = clock.instant();
		Instant roundAt = round.toInstant();
		boolean late = now.isAfter(roundAt.plus(properties.daily().maxDelay()));
		boolean opened = jdbc.sql("""
				INSERT INTO noti_daily_round (round_at, opened_at, late) VALUES (:round, :now, :late)
				ON CONFLICT (round_at) DO NOTHING RETURNING round_at
				""")
			.param("round", Timestamp.from(roundAt))
			.param("now", Timestamp.from(now))
			.param("late", late)
			.query(Timestamp.class)
			.optional()
			.isPresent();
		if (!opened) {
			return Optional.empty();
		}
		String roundKey = round.toLocalDateTime().toString();
		List<Subscribers.Resolved> targets = subscribers.resolve(subscribers.daily(round.toLocalTime()));
		if (late) {
			dispatcher.recordSkips(NotificationKind.DAILY_WEATHER, roundKey, targets.stream()
				.map(t -> new SkippedTarget(t.deviceId(), t.neighborhoodCode() == null ? UNRESOLVED : t.neighborhoodCode(),
						SkipReason.DELAY_EXCEEDED))
				.toList());
			log.warn("정기 날씨 알림 회차 {} 지연 초과로 발송하지 않음({}건)", roundKey, targets.size());
			return Optional.of(new Dispatcher.Report(0, 0, 0, 0, targets.size(), 0));
		}
		List<SkippedTarget> skipped = new ArrayList<>();
		Map<String, List<Subscribers.Resolved>> byNeighborhood = new LinkedHashMap<>();
		for (Subscribers.Resolved target : targets) {
			if (target.neighborhoodCode() == null) {
				skipped.add(new SkippedTarget(target.deviceId(), UNRESOLVED, SkipReason.NEIGHBORHOOD_UNKNOWN));
			}
			else {
				byNeighborhood.computeIfAbsent(target.neighborhoodCode(), k -> new ArrayList<>()).add(target);
			}
		}
		Map<NeighborhoodId, Neighborhood> neighborhoods = neighborhoodApi
			.findNeighborhoods(byNeighborhood.keySet().stream().map(NeighborhoodId::new).toList());
		List<Candidate> candidates = new ArrayList<>();
		for (Map.Entry<String, List<Subscribers.Resolved>> entry : byNeighborhood.entrySet()) {
			String code = entry.getKey();
			Neighborhood neighborhood = neighborhoods.get(new NeighborhoodId(code));
			Map<TemperatureSensitivity, Dispatcher.AlertMessage> messages = new HashMap<>();
			Bundle bundle = neighborhood == null ? null : load(neighborhood, roundAt);
			for (Subscribers.Resolved target : entry.getValue()) {
				if (bundle == null || bundle.empty()) {
					skipped.add(new SkippedTarget(target.deviceId(), code, SkipReason.NO_WEATHER));
					continue;
				}
				Dispatcher.AlertMessage message = messages.computeIfAbsent(target.sensitivity(),
						s -> compose(neighborhood, bundle, s, roundAt));
				candidates.add(new Candidate(target.recipient(), message));
			}
		}
		dispatcher.recordSkips(NotificationKind.DAILY_WEATHER, roundKey, skipped);
		return Optional.of(dispatcher.dispatch(new Round(NotificationKind.DAILY_WEATHER, roundKey,
				roundAt.plus(properties.daily().maxDelay()), null, QuietPolicy.EXEMPT, null), candidates));
	}

	/** 테스트 발송용(FR-NOTI-15): 지금 시각 기준 문구. weather 조회 인터페이스만 쓴다. 날씨가 없으면 빈 값. */
	public Optional<Dispatcher.AlertMessage> preview(Neighborhood neighborhood, TemperatureSensitivity sensitivity) {
		Instant now = clock.instant();
		Bundle bundle = load(neighborhood, now);
		return bundle.empty() ? Optional.empty() : Optional.of(compose(neighborhood, bundle, sensitivity, now));
	}

	/** 동네 하나의 날씨 재료. 각 조회의 실패는 그 값만 비운다. */
	private record Bundle(CurrentObservation observation, HourlyForecast hourly, DailyForecast daily,
			SensitivityFeelings feelings) {

		boolean empty() {
			boolean noObservation = observation == null || observation.isEmpty();
			boolean noHourly = hourly == null || hourly.slots().isEmpty();
			return noObservation && noHourly;
		}

	}

	private Bundle load(Neighborhood neighborhood, Instant at) {
		NeighborhoodId id = neighborhood.id();
		int hours = (int) Math.max(1, properties.daily().forecastSpan().toHours());
		return new Bundle(quietly(() -> weatherApi.findCurrentObservation(id)),
				quietly(() -> weatherApi.findHourlyForecast(id, hours)), quietly(() -> weatherApi.findDailyForecast(id)),
				quietly(() -> feelingApi.findSensitivityFeelings(id, at)));
	}

	private Dispatcher.AlertMessage compose(Neighborhood neighborhood, Bundle bundle, TemperatureSensitivity sensitivity,
			Instant at) {
		CurrentObservation observation = bundle.observation() == null || bundle.observation().isEmpty() ? null
				: bundle.observation();
		Instant spanEnd = at.plus(properties.daily().forecastSpan());
		List<HourlyForecastSlot> slots = bundle.hourly() == null ? List.of()
				: bundle.hourly()
					.slots()
					.stream()
					.filter(s -> !s.forecastAt().isBefore(at.minus(Duration.ofHours(1))) && s.forecastAt().isBefore(spanEnd))
					.toList();
		Double min = null;
		Double max = null;
		LocalDate today = at.atZone(TimeConfig.SEOUL).toLocalDate();
		if (bundle.daily() != null) {
			Optional<DailyForecastDay> day = bundle.daily().days().stream().filter(d -> today.equals(d.date())).findFirst();
			min = day.map(DailyForecastDay::minTemperature).orElse(null);
			max = day.map(DailyForecastDay::maxTemperature).orElse(null);
		}
		if (min == null || max == null) {
			min = slots.stream().map(HourlyForecastSlot::temperature).filter(t -> t != null).min(Double::compare).orElse(null);
			max = slots.stream().map(HourlyForecastSlot::temperature).filter(t -> t != null).max(Double::compare).orElse(null);
		}
		HourlyForecastSlot rain = slots.stream()
			.filter(s -> !s.forecastAt().isBefore(at) && s.precipitationType() != null
					&& s.precipitationType().isPrecipitating())
			.findFirst()
			.orElse(null);
		SensitivityFeeling feeling = bundle.feelings() == null ? null
				: bundle.feelings().find(sensitivity).orElse(null);
		String outfit = feeling == null ? null : quietly(() -> outfitApi
			.recommend(feeling.temperatureTag().level(), precipitation(observation)).outfit().guide());
		YesterdayComparison comparison = feeling == null ? null
				: quietly(() -> feelingApi.compareWithYesterday(neighborhood.id(), sensitivity, at));
		Double temperature = observation == null ? null : observation.temperature();
		if (temperature == null && !slots.isEmpty()) {
			temperature = slots.get(0).temperature();
		}
		DailyContent content = new DailyContent(neighborhood.eupmyeondongName(), temperature,
				observation == null ? null : observation.sky(), min, max, sensitivity,
				feeling == null ? null : feeling.temperatureTag(), feeling == null ? null : feeling.source(), outfit,
				comparison == null || !comparison.comparable() ? null : comparison.phrase(),
				rain == null ? null : rain.forecastAt(), rain == null ? null : rain.precipitationType());
		return templates.daily(neighborhood.id().value(), content);
	}

	private void warmUp(ZonedDateTime round) {
		List<Subscribers.Resolved> targets = subscribers.resolve(subscribers.daily(round.toLocalTime()));
		targets.stream().map(Subscribers.Resolved::neighborhoodCode).filter(c -> c != null).distinct().forEach(code -> {
			NeighborhoodId id = new NeighborhoodId(code);
			quietly(() -> weatherApi.findCurrentObservation(id));
			quietly(() -> weatherApi.findHourlyForecast(id, (int) properties.daily().forecastSpan().toHours()));
		});
	}

	/** weather 강수 형태 → outfit 입력(형식 변환만: 비·소나기 → 비, 눈·비/눈 → 눈). */
	private static Precipitation precipitation(CurrentObservation observation) {
		PrecipitationType type = observation == null ? null : observation.precipitationType();
		if (type == null) {
			return Precipitation.NONE;
		}
		return switch (type) {
			case NONE -> Precipitation.NONE;
			case RAIN, SHOWER -> Precipitation.RAIN;
			case SNOW, RAIN_SNOW -> Precipitation.SNOW;
		};
	}

	private static ZonedDateTime floor(ZonedDateTime time, Duration unit) {
		ZonedDateTime midnight = time.toLocalDate().atStartOfDay(TimeConfig.SEOUL);
		long seconds = Duration.between(midnight, time).toSeconds();
		return midnight.plusSeconds(seconds - seconds % unit.toSeconds());
	}

	private static <T> T quietly(Supplier<T> call) {
		try {
			return call.get();
		}
		catch (RuntimeException e) {
			log.debug("정기 날씨 알림 부가 조회 실패 — 해당 줄 생략: {}", e.toString());
			return null;
		}
	}

}
