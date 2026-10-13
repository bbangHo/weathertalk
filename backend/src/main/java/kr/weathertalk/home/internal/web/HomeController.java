package kr.weathertalk.home.internal.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.home.HomeApi;
import kr.weathertalk.home.HomeView;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.shared.Ids.DeviceId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 홈 조합 (07 §1, SCR-01). 비회원 허용: 비회원은 neighborhoodId 필수, 회원은 없으면 현재 동네.
 * 토큰이 잘못되면 비회원으로 낮추지 않고 인증 실패로 응답한다(07 §1.2 요청자).
 * <ul>
 * <li>{@code temperatureSensitivity}: 비회원이 기기에서 고른 민감도(회원이면 무시).</li>
 * <li>{@code X-Device-Id}·{@code X-Device-Key} 헤더: 기기 확인으로 받은 기기 ID·기기 키. 있으면 notification에 보관된 그 기기의
 * 외출 시각으로 외출 시각 기준 섹션을 만든다(contracts §2.7). 기기 키는 비밀값이라 쿼리에 싣지 않는다.</li>
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
			@RequestHeader(name = "X-Device-Id", required = false) String deviceId,
			@RequestHeader(name = "X-Device-Key", required = false) String deviceKey) {
		return homeApi.compose(requester,
				neighborhoodId == null || neighborhoodId.isBlank() ? null : new NeighborhoodId(neighborhoodId),
				temperatureSensitivity, device(deviceId, deviceKey));
	}

	private static DeviceCredential device(String deviceId, String deviceKey) {
		if (deviceId == null || deviceId.isBlank() || deviceKey == null || deviceKey.isBlank()) {
			return null;
		}
		try {
			return new DeviceCredential(new DeviceId(UUID.fromString(deviceId.strip())), deviceKey.strip());
		}
		catch (IllegalArgumentException e) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "기기 ID 형식이 맞지 않습니다.");
		}
	}

}
