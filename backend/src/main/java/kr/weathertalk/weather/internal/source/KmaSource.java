package kr.weathertalk.weather.internal.source;

import java.time.Instant;
import java.util.List;

import kr.weathertalk.neighborhood.ForecastGrid;

/**
 * 기상청 원천 포트 (03 §3.1). 모든 메서드는 실패 시 {@link SourceException}을 던진다.
 */
public interface KmaSource {

	boolean configured();

	/** 초단기실황(getUltraSrtNcst) — 정시 관측. */
	SourceData.Observation fetchObservation(ForecastGrid grid, Instant issuedAt);

	/** 단기예보(getVilageFcst) — 발표분 전체(약 3일, 행 수 제한으로 자르지 않음). */
	List<SourceData.ShortTermSlot> fetchShortTerm(ForecastGrid grid, Instant issuedAt);

	/** 중기 육상예보(getMidLandFcst). */
	List<SourceData.MidLandDay> fetchMidLand(String regionId, Instant issuedAt);

	/** 중기 기온(getMidTa). */
	List<SourceData.MidTemperatureDay> fetchMidTemperature(String regionId, Instant issuedAt);

	/** 생활기상지수 자외선(getUVIdxV4). */
	List<SourceData.UvValue> fetchUv(String areaNo, Instant issuedAt);

}
