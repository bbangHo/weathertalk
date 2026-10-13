package kr.weathertalk.weather.internal.source;

import java.util.List;

/**
 * 에어코리아 원천 포트 (03 §3.1). 실패 시 {@link SourceException}.
 */
public interface AirKoreaSource {

	boolean configured();

	/**
	 * 근접측정소 목록(getNearbyMsrstnList). 가까운 순.
	 *
	 * @param tmX 측정소 API가 기대하는 TM 좌표(동네 대표 좌표를 변환한 값. 사람의 좌표가 아니다)
	 */
	List<String> findNearbyStations(double tmX, double tmY);

	/** 측정소별 실시간 측정(getMsrstnAcctoRltmMesureDnsty, 최근 24시간). 최신 순. */
	List<SourceData.AirMeasurement> fetchMeasurements(String stationName);

}
