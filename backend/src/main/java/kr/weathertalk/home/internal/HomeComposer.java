package kr.weathertalk.home.internal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import kr.weathertalk.home.HomeApi;
import kr.weathertalk.home.HomeSection;
import kr.weathertalk.home.HomeView;
import kr.weathertalk.home.ViewingNeighborhood;
import kr.weathertalk.member.Requester;
import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.WeatherApi;

/**
 * 홈 조합 1차 (07 §1): 보는 동네 + 현재 날씨·시간별 예보·중기 예보 섹션. 규칙 없음 원칙(07 §1.5) — 호출·조합·섹션 상태·시간 제한만 한다.
 * 신선도·낮밤·등급은 weather 값을 그대로 전달한다.
 */
@Service
class HomeComposer implements HomeApi {

	private final NeighborhoodApi neighborhoodApi;
	private final WeatherApi weatherApi;
	private final SectionRunner sections;

	HomeComposer(NeighborhoodApi neighborhoodApi, WeatherApi weatherApi, SectionRunner sections) {
		this.neighborhoodApi = neighborhoodApi;
		this.weatherApi = weatherApi;
		this.sections = sections;
	}

	@Override
	public HomeView compose(Requester requester, NeighborhoodId neighborhoodId) {
		Optional<CurrentNeighborhood> current = requester instanceof Requester.Member member
				? neighborhoodApi.findCurrentNeighborhood(member.memberId())
				: Optional.empty();
		NeighborhoodId viewingId = neighborhoodId != null ? neighborhoodId
				: current.map(c -> c.neighborhood().id())
					.orElseThrow(() -> unresolved("NOT_SPECIFIED", "보는 동네를 정해 주세요."));
		Neighborhood neighborhood = neighborhoodApi.findNeighborhood(viewingId)
			.orElseThrow(() -> unresolved("NOT_FOUND", "없는 동네입니다."));
		NeighborhoodId id = neighborhood.id();

		CompletableFuture<HomeSection<CurrentObservation>> currentWeather = sections.start("current-weather",
				() -> weatherApi.findCurrentObservation(id), CurrentObservation::isEmpty);
		CompletableFuture<HomeSection<HourlyForecast>> hourly = sections.start("hourly-forecast",
				() -> weatherApi.findHourlyForecast(id, null), forecast -> forecast.slots().isEmpty());
		CompletableFuture<HomeSection<DailyForecast>> daily = sections.start("daily-forecast",
				() -> weatherApi.findDailyForecast(id), DailyForecast::isEmpty);

		boolean isCurrent = current.map(c -> c.neighborhood().id().equals(id)).orElse(false);
		return new HomeView(new ViewingNeighborhood(id.value(), neighborhood.sidoName(), neighborhood.sigunguName(),
				neighborhood.eupmyeondongName(), neighborhood.fullName(), neighborhood.shortName(), isCurrent),
				SectionRunner.join(currentWeather), SectionRunner.join(hourly), SectionRunner.join(daily));
	}

	private static DomainException unresolved(String reason, String message) {
		return new DomainException(ErrorCode.VIEWING_NEIGHBORHOOD_UNRESOLVED, message, Map.of("reason", reason));
	}

}
