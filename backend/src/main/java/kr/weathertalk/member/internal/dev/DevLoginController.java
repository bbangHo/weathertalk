package kr.weathertalk.member.internal.dev;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.member.internal.AuthService;
import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.web.LoginResponse;
import kr.weathertalk.shared.profile.DevelopmentOnly;

/**
 * 개발 전용 로그인 (implementation-plan §2 후순위 원칙). 실제 소셜 로그인과 같은 흐름을 탄다:
 * 처음 보는 ID면 가입 티켓, 이미 가입한 ID면 토큰. 운영(prod) 프로파일에는 등록되지 않는다.
 */
@RestController
@DevelopmentOnly
class DevLoginController {

	/**
	 * @param providerUserId 임의 고유 ID(제공자 고유 ID 흉내)
	 * @param email 흉내 이메일(선택)
	 */
	record DevLoginRequest(String providerUserId, String email) {
	}

	private final AuthService authService;

	DevLoginController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/api/v1/dev/login")
	LoginResponse login(@RequestBody DevLoginRequest request) {
		return LoginResponse.from(authService
			.login(new ProviderCredential(AuthProvider.DEV, request.providerUserId(), null, request.email())));
	}

}
