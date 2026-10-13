package kr.weathertalk.neighborhood;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네(행정동) 조회 결과 (contracts.md §2.2 동네 조회). 이름은 항상 이 동네 자신의 이름이다(BR-NBR-11).
 *
 * @param id 동네 ID (값 = 행정동 코드)
 */
public record Neighborhood(NeighborhoodId id, String sidoName, String sigunguName, String eupmyeondongName,
		GeoPoint representativePoint, ForecastGrid forecastGrid, NeighborhoodStatus status) {

	public String administrativeCode() {
		return id.value();
	}

	/** "시도 시군구 읍면동" */
	public String fullName() {
		return sidoName + " " + sigunguName + " " + eupmyeondongName;
	}

	/** "시군구 읍면동" */
	public String shortName() {
		return sigunguName + " " + eupmyeondongName;
	}

}
