package kr.weathertalk.weather.internal.collect;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.store.AirStore;
import kr.weathertalk.weather.internal.store.MidTermStore;
import kr.weathertalk.weather.internal.store.ObservationStore;
import kr.weathertalk.weather.internal.store.ShortTermStore;
import kr.weathertalk.weather.internal.store.UvStore;

/**
 * 지난 수집 결과 정리 (FR-WEA-11, legacy 04-#20). 모든 칸이 지난 단기예보 발표분, weather.retention.observation보다 오래된
 * 실황·대기질, 대체된 중기·자외선 발표분을 지운다. 수집 단위마다 마지막 정상값은 남긴다(실패 시 대체 응답용).
 */
@Service
public class RetentionCleanupService {

	private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

	private final ShortTermStore shortTerms;
	private final ObservationStore observations;
	private final AirStore air;
	private final MidTermStore midTerms;
	private final UvStore uv;
	private final WeatherProperties properties;
	private final Clock clock;

	public RetentionCleanupService(ShortTermStore shortTerms, ObservationStore observations, AirStore air,
			MidTermStore midTerms, UvStore uv, WeatherProperties properties, Clock clock) {
		this.shortTerms = shortTerms;
		this.observations = observations;
		this.air = air;
		this.midTerms = midTerms;
		this.uv = uv;
		this.properties = properties;
		this.clock = clock;
	}

	public void cleanup() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.retention().observation());
		int shortTerm = shortTerms.deletePast(now);
		int observation = observations.deleteOlderThan(cutoff);
		int airQuality = air.deleteOlderThan(cutoff);
		int mid = midTerms.deleteSuperseded();
		int uvIndex = uv.deleteSuperseded();
		log.info("날씨 수집 결과 정리: 단기 발표분 {}, 실황 {}, 대기질 {}, 중기 {}, 자외선 {}", shortTerm, observation, airQuality, mid,
				uvIndex);
	}

}
