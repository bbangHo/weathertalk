package kr.weathertalk.weather.internal.region;

import java.util.List;

import org.springframework.stereotype.Component;

import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.store.AirStationStore;

/**
 * 동네 → 가까운 순 측정소 대응 (03 §3.6). 동네 대표 좌표(사람의 위치 아님)에서 동기화된 측정소 목록(WGS84) 중 가까운
 * station-fallback-count곳을 PostGIS로 계산한다. 외부 호출이 없어 대응을 따로 저장하지 않고 매번 계산한다 — 목록 동기화로
 * 신설·폐지가 곧바로 반영된다.
 * <p>
 * 근접측정소 목록(getNearbyMsrstnList)을 부르는 비상 경로는 두지 않는다: 목록이 비는 경우는 측정소정보 서비스 자체 장애일 때인데
 * 근접측정소 목록도 같은 서비스라 함께 실패하고, 동네마다 부르면 기능별 하루 한도(500)를 금방 쓴다. 목록이 비면
 * {@link AirStationSync#ensureListed()}가 목록을 다시 받는다(실패 백오프 적용).
 */
@Component
public class AirStationMapper {

	private final AirStationStore store;
	private final AirStationSync sync;
	private final WeatherProperties properties;

	public AirStationMapper(AirStationStore store, AirStationSync sync, WeatherProperties properties) {
		this.store = store;
		this.sync = sync;
		this.properties = properties;
	}

	/**
	 * 가까운 순 측정소(최대 station-fallback-count곳).
	 *
	 * @param syncIfEmpty 목록이 비었으면 받아 본다(요청 시 수집·사전 갱신). 과거 관측 조회는 저장분만 쓴다
	 */
	public List<String> stations(Neighborhood neighborhood, boolean syncIfEmpty) {
		if (syncIfEmpty && !sync.ensureListed()) {
			return List.of();
		}
		return store.nearest(neighborhood.representativePoint(), properties.air().stationFallbackCount());
	}

}
