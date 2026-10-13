package kr.weathertalk.weather.internal.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.weather.CurrentObservation;
import kr.weathertalk.weather.DailyForecast;
import kr.weathertalk.weather.HourlyForecast;
import kr.weathertalk.weather.WeatherApi;

/**
 * 동네별 현재 날씨·시간별·일별 예보 (FR-WEA-01~03). 비회원에게도 공개한다(BR-WEA-22). 외부 장애는 오류가 아니라 신선도로 드러난다.
 */
@RestController
class WeatherController {

	private final WeatherApi weatherApi;

	WeatherController(WeatherApi weatherApi) {
		this.weatherApi = weatherApi;
	}

	@GetMapping("/api/v1/weather/neighborhoods/{neighborhoodId}/current")
	CurrentObservation current(@PathVariable String neighborhoodId) {
		return weatherApi.findCurrentObservation(new NeighborhoodId(neighborhoodId));
	}

	@GetMapping("/api/v1/weather/neighborhoods/{neighborhoodId}/hourly")
	HourlyForecast hourly(@PathVariable String neighborhoodId, @RequestParam(required = false) Integer hours) {
		return weatherApi.findHourlyForecast(new NeighborhoodId(neighborhoodId), hours);
	}

	@GetMapping("/api/v1/weather/neighborhoods/{neighborhoodId}/daily")
	DailyForecast daily(@PathVariable String neighborhoodId) {
		return weatherApi.findDailyForecast(new NeighborhoodId(neighborhoodId));
	}

}
