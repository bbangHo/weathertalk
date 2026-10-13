package kr.weathertalk.weather.internal.fieldreport;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.precip.PrecipEpisodeRepository;

/**
 * 현장 제보 정리 (FR-WEA-10): weather.field-report.retention이 지난 제보와, 만료된 발행 기록(현장 제보 강수: rain-suppress 경과,
 * 강수 구간: 예상 시작 + episode-expiry 경과)을 지운다. 정기 실행은 WeatherJobs(회차 단위 공유 잠금).
 */
@Service
public class FieldReportCleanupService {

	private static final Logger log = LoggerFactory.getLogger(FieldReportCleanupService.class);

	private final FieldReportRepository reports;
	private final PrecipEpisodeRepository episodes;
	private final WeatherProperties properties;
	private final Clock clock;

	FieldReportCleanupService(FieldReportRepository reports, PrecipEpisodeRepository episodes,
			WeatherProperties properties, Clock clock) {
		this.reports = reports;
		this.episodes = episodes;
		this.properties = properties;
		this.clock = clock;
	}

	public void cleanup() {
		Instant now = clock.instant();
		int deletedReports = reports.deleteReportedBefore(now.minus(properties.fieldReport().retention()));
		int deletedPublications = reports.deletePrecipPublicationsBefore(now.minus(properties.fieldReport().rainSuppress()));
		int deletedEpisodes = episodes.deleteStartedAtOrBefore(null, now.minus(properties.precipStart().episodeExpiry()));
		log.info("현장 제보 정리: 제보 {}, 현장 제보 강수 발행 기록 {}, 강수 구간 기록 {}", deletedReports, deletedPublications,
				deletedEpisodes);
	}

}
