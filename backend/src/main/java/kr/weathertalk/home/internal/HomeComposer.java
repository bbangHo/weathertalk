package kr.weathertalk.home.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import kr.weathertalk.community.CommunityApi;
import kr.weathertalk.community.PostSummary;
import kr.weathertalk.feeling.CalibrationOffer;
import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.SensitivityFeeling;
import kr.weathertalk.feeling.SensitivityFeelings;
import kr.weathertalk.feeling.YesterdayComparison;
import kr.weathertalk.home.DepartureOutfit;
import kr.weathertalk.home.FeedbackStatus;
import kr.weathertalk.home.HomeApi;
import kr.weathertalk.home.HomeSection;
import kr.weathertalk.home.HomeView;
import kr.weathertalk.home.PersonalBasis;
import kr.weathertalk.home.ViewingNeighborhood;
import kr.weathertalk.home.internal.config.HomeProperties;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.notification.DepartureTimes;
import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.notification.NotificationApi;
import kr.weathertalk.outfit.DepartureCondition;
import kr.weathertalk.outfit.OutfitApi;
import kr.weathertalk.outfit.OutfitRecommendation;
import kr.weathertalk.outfit.Precipitation;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.FieldReportStatus;
import kr.weathertalk.weather.ForecastDivergence;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.WeatherApi;

/**
 * 홈 조합 (07 §1): 보는 동네 + 날씨 섹션 + 민감도별 체감 3그룹·피드백 버튼 상태·옷 추천·어제와 비교·외출 시각 기준 + 동네 인기글 + 예보 vs 주민 차이 배지·현장 제보 현황(weather 판정·집계 그대로 표시) + 민감도 보정 제안 카드(회원, feeling 조회 그대로).
 * 규칙 없음 원칙(07 §1.5) — 호출·조합·섹션 상태·요청자에 따른 섹션 선택·시간 제한만 한다. 산출 출처·비교 가능 여부·옷차림은 소유 모듈 값 그대로다.
 * 옷 추천·어제 비교·외출 시각 기준은 민감도별 체감이 실패하면 조회하지 않는다(07 §1.4-3).
 */
@Service
class HomeComposer implements HomeApi {

	private final NeighborhoodApi neighborhoodApi;
	private final WeatherApi weatherApi;
	private final MemberApi memberApi;
	private final FeelingApi feelingApi;
	private final OutfitApi outfitApi;
	private final CommunityApi communityApi;
	private final NotificationApi notificationApi;
	private final SectionRunner sections;
	private final HomeProperties properties;

	HomeComposer(NeighborhoodApi neighborhoodApi, WeatherApi weatherApi, MemberApi memberApi, FeelingApi feelingApi,
			OutfitApi outfitApi, CommunityApi communityApi, NotificationApi notificationApi, SectionRunner sections,
			HomeProperties properties) {
		this.neighborhoodApi = neighborhoodApi;
		this.weatherApi = weatherApi;
		this.memberApi = memberApi;
		this.feelingApi = feelingApi;
		this.outfitApi = outfitApi;
		this.communityApi = communityApi;
		this.notificationApi = notificationApi;
		this.sections = sections;
		this.properties = properties;
	}

	@Override
	public HomeView compose(Requester requester, NeighborhoodId neighborhoodId,
			TemperatureSensitivity deviceSensitivity, DeviceCredential device) {
		Requester.Member member = requester instanceof Requester.Member m ? m : null;
		Optional<CurrentNeighborhood> current = member != null ? neighborhoodApi.findCurrentNeighborhood(member.memberId())
				: Optional.empty();
		NeighborhoodId viewingId = neighborhoodId != null ? neighborhoodId
				: current.map(c -> c.neighborhood().id())
					.orElseThrow(() -> unresolved("NOT_SPECIFIED", "보는 동네를 정해 주세요."));
		Neighborhood neighborhood = neighborhoodApi.findNeighborhood(viewingId)
			.orElseThrow(() -> unresolved("NOT_FOUND", "없는 동네입니다."));
		NeighborhoodId id = neighborhood.id();
		boolean isCurrent = current.map(c -> c.neighborhood().id().equals(id)).orElse(false);
		PersonalBasis personal = personal(member, deviceSensitivity);
		TemperatureSensitivity basis = personal.basisGroup();

		CompletableFuture<HomeSection<CurrentObservation>> currentWeather = sections.start("current-weather",
				() -> weatherApi.findCurrentObservation(id), CurrentObservation::isEmpty);
		CompletableFuture<HomeSection<HourlyForecast>> hourly = sections.start("hourly-forecast",
				() -> weatherApi.findHourlyForecast(id, null), forecast -> forecast.slots().isEmpty());
		CompletableFuture<HomeSection<DailyForecast>> daily = sections.start("daily-forecast",
				() -> weatherApi.findDailyForecast(id), DailyForecast::isEmpty);

		CompletableFuture<HomeSection<SensitivityFeelings>> feelings = sections.start("sensitivity-feelings",
				() -> feelingApi.findSensitivityFeelings(id, null), f -> f.groups().isEmpty());
		CompletableFuture<HomeSection<FeedbackStatus>> feedback = member == null ? null
				: sections.start("feedback-status",
						() -> new FeedbackStatus(isCurrent,
								isCurrent ? feelingApi.findRecentRecord(member.memberId(), id).orElse(null) : null),
						status -> false);
		CompletableFuture<HomeSection<OutfitRecommendation>> outfit = sections.after("outfit", feelings,
				f -> f.find(basis)
					.map(feeling -> outfitApi.recommend(feeling.temperatureTag().level(),
							precipitation(SectionRunner.join(currentWeather).data())))
					.orElse(null),
				o -> false);
		CompletableFuture<HomeSection<YesterdayComparison>> yesterday = sections.after("yesterday-comparison",
				feelings, f -> feelingApi.compareWithYesterday(id, basis, null), c -> !c.comparable());
		CompletableFuture<HomeSection<DepartureOutfit>> departure = device == null
				? null
				: sections.after("departure-outfit", feelings,
						f -> departure(id, basis, device, SectionRunner.join(hourly).data()),
						d -> d.recommendation() == null);
		CompletableFuture<HomeSection<List<PostSummary>>> popular = sections.start("popular-posts",
				() -> communityApi.findPopularPosts(id, requester)
					.stream()
					.limit(properties.popularPostsCount())
					.toList(),
				List::isEmpty);

		// 차이 판정은 현재 날씨와 같은 수집 사본을 쓰므로 현재 날씨 섹션 뒤에 부른다(같은 수집을 두 섹션이 기다리지 않게)
		CompletableFuture<HomeSection<ForecastDivergence>> divergence = sections.after("divergence", currentWeather,
				c -> weatherApi.findForecastDivergence(id), d -> !d.hasBadge());
		CompletableFuture<HomeSection<CalibrationOffer>> calibration = member == null ? null
				: sections.start("calibration-offer",
						() -> feelingApi.findCalibrationOffer(member.memberId()).orElse(null), offer -> false);
		CompletableFuture<HomeSection<FieldReportStatus>> fieldReports = sections.start("field-reports",
				() -> weatherApi.findFieldReportStatus(id, member == null ? null : member.memberId()),
				FieldReportStatus::isEmpty);

		return new HomeView(new ViewingNeighborhood(id.value(), neighborhood.sidoName(), neighborhood.sigunguName(),
				neighborhood.eupmyeondongName(), neighborhood.fullName(), neighborhood.shortName(), isCurrent),
				SectionRunner.join(currentWeather), SectionRunner.join(hourly), SectionRunner.join(daily), personal,
				SectionRunner.join(feelings), feedback == null ? null : SectionRunner.join(feedback),
				SectionRunner.join(outfit), SectionRunner.join(yesterday),
				departure == null ? null : SectionRunner.join(departure), SectionRunner.join(popular),
				SectionRunner.join(divergence), SectionRunner.join(fieldReports),
				calibration == null ? null : SectionRunner.join(calibration));
	}

	/** 내 그룹 선택(07 §1.3): 회원은 회원 민감도, 비회원은 기기 민감도, 없으면 '보통' 기준 + 고르기 안내. */
	private PersonalBasis personal(Requester.Member member, TemperatureSensitivity deviceSensitivity) {
		if (member != null) {
			TemperatureSensitivity mine = memberApi.findProfile(member.memberId())
				.map(MemberProfile::temperatureSensitivity)
				.orElse(TemperatureSensitivity.NORMAL);
			return new PersonalBasis(mine, mine, false);
		}
		if (deviceSensitivity != null) {
			return new PersonalBasis(deviceSensitivity, deviceSensitivity, false);
		}
		return new PersonalBasis(null, TemperatureSensitivity.NORMAL, true);
	}

	/**
	 * 외출 시각 기준: notification `기기의 외출 시각 조회`가 정한 각 외출 시각의 다음 도래 시각으로 기준 그룹 체감을 조회해 outfit에 넘긴다.
	 * 다음 외출 시각 선택 규칙은 notification 소유다(contracts §2.7) — home은 결과를 표시만 한다. 기기에 외출 시각이 없으면 빈 섹션.
	 */
	private DepartureOutfit departure(NeighborhoodId id, TemperatureSensitivity basis, DeviceCredential device,
			HourlyForecast hourly) {
		DepartureTimes times = notificationApi.findDepartureTimes(device);
		List<DepartureOutfit.Departure> departures = new ArrayList<>();
		List<DepartureCondition> conditions = new ArrayList<>();
		for (DepartureTimes.Departure time : times.departures()) {
			Instant departureAt = time.nextAt();
			Optional<SensitivityFeeling> feeling = feelingApi.findSensitivityFeeling(id, basis, departureAt);
			feeling.ifPresent(f -> {
				departures.add(new DepartureOutfit.Departure(departureAt, f));
				conditions.add(new DepartureCondition(departureAt, f.temperatureTag().level(),
						precipitationAt(hourly, f.basisTime())));
			});
		}
		return new DepartureOutfit(departures,
				conditions.isEmpty() ? null : outfitApi.recommendForDepartures(conditions));
	}

	private static Precipitation precipitationAt(HourlyForecast hourly, Instant basisTime) {
		if (hourly == null) {
			return Precipitation.NONE;
		}
		return hourly.slots()
			.stream()
			.filter(slot -> slot.forecastAt().equals(basisTime))
			.findFirst()
			.map(slot -> precipitation(slot.precipitationType()))
			.orElse(Precipitation.NONE);
	}

	private static Precipitation precipitation(CurrentObservation observation) {
		return observation == null ? Precipitation.NONE : precipitation(observation.precipitationType());
	}

	/** weather 강수 형태를 outfit 입력 형식으로 옮긴다(판단 없음: 비·소나기 → 비, 눈·비/눈 → 눈). */
	private static Precipitation precipitation(PrecipitationType type) {
		if (type == null) {
			return Precipitation.NONE;
		}
		return switch (type) {
			case NONE -> Precipitation.NONE;
			case RAIN, SHOWER -> Precipitation.RAIN;
			case SNOW, RAIN_SNOW -> Precipitation.SNOW;
		};
	}

	private static DomainException unresolved(String reason, String message) {
		return new DomainException(ErrorCode.VIEWING_NEIGHBORHOOD_UNRESOLVED, message, Map.of("reason", reason));
	}

}
