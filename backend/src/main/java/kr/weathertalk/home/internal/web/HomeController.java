package kr.weathertalk.home.internal.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.home.HomeApi;
import kr.weathertalk.home.HomeView;
import kr.weathertalk.member.Requester;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 홈 조합 (07 §1, SCR-01). 비회원 허용: 비회원은 neighborhoodId 필수, 회원은 없으면 현재 동네.
 * 토큰이 잘못되면 비회원으로 낮추지 않고 인증 실패로 응답한다(07 §1.2 요청자).
 */
@RestController
class HomeController {

	private final HomeApi homeApi;

	HomeController(HomeApi homeApi) {
		this.homeApi = homeApi;
	}

	@GetMapping("/api/v1/home")
	HomeView home(Requester requester, @RequestParam(required = false) String neighborhoodId) {
		return homeApi.compose(requester,
				neighborhoodId == null || neighborhoodId.isBlank() ? null : new NeighborhoodId(neighborhoodId));
	}

}
