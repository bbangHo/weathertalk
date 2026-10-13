package kr.weathertalk.weather.internal.precip;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.weather.Freshness;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.internal.collect.FreshnessRules;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.conversion.RainPhraseCalculator;
import kr.weathertalk.weather.internal.source.SourceData;
import kr.weathertalk.weather.internal.store.ObservationStore;
import kr.weathertalk.weather.internal.store.ShortTermStore;

/**
 * 강수 시작 판정 → 강수시작예보됨 (FR-WEA-09, BR-WEA-20). 한 예보 격자의 단기예보·실황이 새로 수집된 직후 그 격자의 활성 동네마다 판정한다.
 * <ol>
 * <li>지금(현재 시 정각 칸 포함)부터 weather.precip-start.lead-window 안에서 강수 형태가 있고 강수확률 ≥ min-pop인 가장 이른 칸 =
 * 예상 시작 시각. 예보 조회와 같은 사본(쓸 수 있는 최신 발표분)을 쓴다(legacy 04-#17).</li>
 * <li>지금 강수 중이 아니어야 한다: 실황(정상·지연) 강수 형태, 실황이 '없음'이면 현재 시 예보 칸의 강수 형태가 '없음'.</li>
 * <li>같은 강수 구간을 이미 발행하지 않았어야 한다({@link PrecipEpisodeRecorder}).</li>
 * </ol>
 * 판정 실패는 수집 결과에 영향을 주지 않는다(로그만 남긴다).
 */
@Component
public class PrecipStartJudge {

	private static final Logger log = LoggerFactory.getLogger(PrecipStartJudge.class);

	/** 강수 시작 후보: 조건 1을 만족한 가장 이른 예보 칸. */
	record Candidate(Instant startsAt, PrecipitationType type, String phrase, Instant basisIssuedAt) {
	}

	private final NeighborhoodApi neighborhoods;
	private final ShortTermStore shortTerms;
	private final ObservationStore observations;
	private final FreshnessRules freshness;
	private final RainPhraseCalculator rainPhrase;
	private final PrecipEpisodeRecorder recorder;
	private final WeatherProperties properties;
	private final Clock clock;

	PrecipStartJudge(NeighborhoodApi neighborhoods, ShortTermStore shortTerms, ObservationStore observations,
			FreshnessRules freshness, RainPhraseCalculator rainPhrase, PrecipEpisodeRecorder recorder,
			WeatherProperties properties, Clock clock) {
		this.neighborhoods = neighborhoods;
		this.shortTerms = shortTerms;
		this.observations = observations;
		this.freshness = freshness;
		this.rainPhrase = rainPhrase;
		this.recorder = recorder;
		this.properties = properties;
		this.clock = clock;
	}

	/** 수집 성공 직후 호출한다. 판정 오류는 수집 경로로 번지지 않는다. */
	public void judgeAfterCollection(ForecastGrid grid) {
		try {
			judge(grid);
		}
		catch (RuntimeException e) {
			log.warn("강수 시작 판정 실패 (격자 {}:{}): {}", grid.nx(), grid.ny(), e.toString());
		}
	}

	/** @return 이번 판정으로 발행한 동네 수 */
	public int judge(ForecastGrid grid) {
		List<NeighborhoodId> targets = neighborhoods.findActiveNeighborhoods(grid);
		if (targets.isEmpty()) {
			return 0;
		}
		Instant now = clock.instant();
		Instant hour = now.truncatedTo(ChronoUnit.HOURS);
		Optional<ShortTermStore.Issue> issue = shortTerms.findLatestIssuedAtOrBefore(grid, now);
		List<SourceData.ShortTermSlot> slots = issue.map(ShortTermStore.Issue::slots).orElse(List.of());
		boolean remaining = slots.stream().anyMatch(s -> !s.forecastAt().isBefore(hour));
		if (issue.isEmpty() || freshness.shortTerm(issue.get().issuedAt(), now, remaining) == Freshness.NONE) {
			slots = List.of();
		}
		PrecipEpisodeRecorder.Situation situation = new PrecipEpisodeRecorder.Situation(now,
				currentlyRaining(grid, slots, hour, now),
				candidate(slots, hour, now, issue.map(ShortTermStore.Issue::issuedAt).orElse(null)));
		int published = 0;
		for (NeighborhoodId target : targets) {
			if (recorder.record(target, situation)) {
				published++;
			}
		}
		return published;
	}

	private Boolean currentlyRaining(ForecastGrid grid, List<SourceData.ShortTermSlot> slots, Instant hour,
			Instant now) {
		Optional<SourceData.Observation> observation = observations.findLatestAtOrBefore(grid, now);
		if (observation.isPresent() && observation.get().precipitationType() != null
				&& freshness.observation(observation.get().observedAt(), now) != Freshness.NONE) {
			return observation.get().precipitationType().isPrecipitating();
		}
		return slots.stream()
			.filter(s -> s.forecastAt().equals(hour) && s.precipitationType() != null)
			.findFirst()
			.map(s -> s.precipitationType().isPrecipitating())
			.orElse(null);
	}

	private Candidate candidate(List<SourceData.ShortTermSlot> slots, Instant hour, Instant now, Instant issuedAt) {
		WeatherProperties.PrecipStart rule = properties.precipStart();
		Instant limit = now.plus(rule.leadWindow());
		return slots.stream()
			.filter(s -> !s.forecastAt().isBefore(hour) && !s.forecastAt().isAfter(limit))
			.filter(s -> s.precipitationType() != null && s.precipitationType().isPrecipitating())
			.filter(s -> s.precipitationProbability() != null && s.precipitationProbability() >= rule.minPop())
			.min(Comparator.comparing(SourceData.ShortTermSlot::forecastAt))
			.map(s -> new Candidate(s.forecastAt(), s.precipitationType(),
					rainPhrase.phrase(s.precipitationType(), s.precipitation(), s.snowfall()), issuedAt))
			.orElse(null);
	}

}
