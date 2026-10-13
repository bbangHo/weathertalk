package kr.weathertalk.neighborhood.internal.web;

import java.util.List;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.GeoPoint;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodStatus;

/** 동네 응답. 이름은 항상 이 동네 자신의 이름(BR-NBR-11). */
public record NeighborhoodResponse(String id, String sidoName, String sigunguName, String eupmyeondongName,
		String fullName, String shortName, GeoPoint representativePoint, ForecastGrid forecastGrid,
		NeighborhoodStatus status) {

	public static NeighborhoodResponse from(Neighborhood n) {
		return new NeighborhoodResponse(n.id().value(), n.sidoName(), n.sigunguName(), n.eupmyeondongName(),
				n.fullName(), n.shortName(), n.representativePoint(), n.forecastGrid(), n.status());
	}

	public static List<NeighborhoodResponse> from(List<Neighborhood> neighborhoods) {
		return neighborhoods.stream().map(NeighborhoodResponse::from).toList();
	}

}
