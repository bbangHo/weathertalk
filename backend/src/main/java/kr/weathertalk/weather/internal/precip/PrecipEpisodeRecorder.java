package kr.weathertalk.weather.internal.precip;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.weather.PrecipitationStartForecasted;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.precip.PrecipEpisodeRepository.Episode;

/**
 * 한 동네의 강수 구간 발행 판정·기록 (BR-WEA-20 3번 조건과 구간 소멸). 동네 단위 잠금 안에서 판정·기록·발행을 한 트랜잭션으로 해
 * 실황·단기예보 수집이 동시에 끝나도 같은 구간을 두 번 발행하지 않는다(03 §3.9).
 * <p>
 * 같은 강수 구간: 기록된 예상 시작 시각과의 차이가 same-episode-tolerance 이내이거나, 이미 시작한 기록 구간에서 마지막 강수 확인 후
 * dry-gap이 지나기 전에 다시 강수가 예보된 경우(잠깐 그쳤다 다시 오는 비를 새 구간으로 보지 않는다 — 03 §10 구현 메모).
 * 기록은 예상 시작 + episode-expiry가 지나거나, 시작 후 강수 없음이 dry-gap 이상 이어지면 소멸한다.
 */
@Component
class PrecipEpisodeRecorder {

	/** 한 동네·한 판정 시점의 입력. @param currentlyRaining 지금 강수 중인가. 알 수 없으면 null */
	record Situation(Instant now, Boolean currentlyRaining, PrecipStartJudge.Candidate candidate) {
	}

	private final PrecipEpisodeRepository episodes;
	private final ApplicationEventPublisher events;
	private final WeatherProperties properties;

	PrecipEpisodeRecorder(PrecipEpisodeRepository episodes, ApplicationEventPublisher events,
			WeatherProperties properties) {
		this.episodes = episodes;
		this.events = events;
		this.properties = properties;
	}

	/** @return 이번에 발행했으면 참 */
	@Transactional
	public boolean record(NeighborhoodId neighborhoodId, Situation situation) {
		WeatherProperties.PrecipStart rule = properties.precipStart();
		String code = neighborhoodId.value();
		Instant now = situation.now();
		episodes.lock(code);
		episodes.deleteStartedAtOrBefore(code, now.minus(rule.episodeExpiry()));
		if (Boolean.TRUE.equals(situation.currentlyRaining())) {
			episodes.markWet(code, now);
		}
		else if (Boolean.FALSE.equals(situation.currentlyRaining())) {
			for (Episode episode : episodes.find(code)) {
				if (started(episode, now) && !lastWet(episode).plus(rule.dryGap()).isAfter(now)) {
					episodes.delete(code, episode.expectedStartAt());
				}
			}
		}
		// 지금 강수 중이거나 알 수 없으면 '시작' 예보가 아니다(조건 2)
		PrecipStartJudge.Candidate candidate = situation.candidate();
		if (!Boolean.FALSE.equals(situation.currentlyRaining()) || candidate == null) {
			return false;
		}
		List<Episode> existing = episodes.find(code);
		if (existing.stream().anyMatch(e -> sameEpisode(e, candidate.startsAt(), now, rule))) {
			return false;
		}
		episodes.insert(code, candidate.startsAt(), now, candidate.basisIssuedAt());
		events.publishEvent(new PrecipitationStartForecasted(UUID.randomUUID(), neighborhoodId, candidate.startsAt(),
				candidate.type(), candidate.phrase(), candidate.basisIssuedAt()));
		return true;
	}

	private static boolean sameEpisode(Episode episode, Instant start, Instant now, WeatherProperties.PrecipStart rule) {
		if (Duration.between(episode.expectedStartAt(), start).abs().compareTo(rule.sameEpisodeTolerance()) <= 0) {
			return true;
		}
		return started(episode, now) && Duration.between(lastWet(episode), start).compareTo(rule.dryGap()) < 0;
	}

	private static boolean started(Episode episode, Instant now) {
		return !episode.expectedStartAt().isAfter(now);
	}

	private static Instant lastWet(Episode episode) {
		Instant lastWet = episode.lastWetAt();
		return lastWet != null && lastWet.isAfter(episode.expectedStartAt()) ? lastWet : episode.expectedStartAt();
	}

}
