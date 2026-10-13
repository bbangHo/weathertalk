package kr.weathertalk.weather.internal.source;

import java.util.List;

/**
 * 에어코리아 원천 포트 (03 §3.1). 실패 시 {@link SourceException}. 목록은 어댑터가 페이지를 넘겨 전부 모아 돌려준다.
 * <p>
 * 개발계정은 기능별 하루 500건 한도라 동네·측정소마다 부르는 기능(근접측정소 목록, 측정소별 실시간 측정)은 쓰지 않는다
 * (03 구현 메모 §13).
 */
public interface AirKoreaSource {

	/** 시도별 실시간 측정의 '전국' 값(기술문서 대기오염정보 v1.4 sidoName). */
	String NATIONWIDE = "전국";

	boolean configured();

	/** 측정소 목록 조회(getMsrstnList) — 전국 측정소. 받은 수가 전체 결과 수에 못 미치면 실패(일부 목록으로 폐지 판정하지 않게). */
	List<SourceData.AirStation> fetchStations();

	/**
	 * 시도별 실시간 측정정보 조회(getCtprvnRltmMesureDnsty) — 그 시도 모든 측정소의 최신 측정.
	 *
	 * @param sidoName 기술문서의 sidoName 값(전국, 서울, …, 전남광주)
	 */
	List<SourceData.StationAirMeasurement> fetchRealtime(String sidoName);

}
