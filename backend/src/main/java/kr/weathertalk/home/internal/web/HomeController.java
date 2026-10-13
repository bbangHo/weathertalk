package kr.weathertalk.home.internal.web;

import java.time.LocalTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.home.HomeApi;
import kr.weathertalk.home.HomeView;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 홈 조합 (07 §1, SCR-01). 비회원 허용: 비회원은 neighborhoodId 필수, 회원은 없으면 현재 동네.
 * 토큰이 잘못되면 비회원으로 낮추지 않고 인증 실패로 응답한다(07 §1.2 요청자).
 * <ul>
 * <li>{@code temperatureSensitivity}: 비회원이 기기에서 고른 민감도(회원이면 무시).</li>
 * <li>{@code departureTimes}: 기기의 외출 시각(HH:mm, 여러 개). 외출 시각 저장(M7 notification) 전까지 요청으로 받는다.</li>
 * </ul>
 */
@RestController
class HomeController {

	private final HomeApi homeApi;

	HomeController(HomeApi homeApi) {
		this.homeApi = homeApi;
	}

	@GetMapping("/api/v1/home")
	HomeView home(Requester requester, @RequestParam(required = false) String neighborhoodId,
			@RequestParam(required = false) TemperatureSensitivity temperatureSensitivity,
			@RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") List<LocalTime> departureTimes) {
		return homeApi.compose(requester,
				neighborhoodId == null || neighborhoodId.isBlank() ? null : new NeighborhoodId(neighborhoodId),
				temperatureSensitivity, departureTimes);
	}

}
