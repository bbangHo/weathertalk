package kr.weathertalk.member.internal.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.AuthService;
import kr.weathertalk.member.internal.TermsService;
import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.web.LoginResponse.TermsResponse;
import kr.weathertalk.member.internal.web.LoginResponse.TokenResponse;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 소셜 로그인·로그인 수단 목록·가입 완료·토큰 재발급·로그아웃·약관 목록 (FR-MEM-02~05). 개발 전용 로그인은 {@code dev} 패키지에 따로 둔다.
 */
@RestController
class AuthController {

	record AgreementRequest(String kind, Integer version, boolean agreed) {

		TermsService.AgreementInput toInput() {
			return new TermsService.AgreementInput(kind, version, agreed);
		}

		static List<TermsService.AgreementInput> toInputs(List<AgreementRequest> requests) {
			return requests == null ? List.of() : requests.stream().map(AgreementRequest::toInput).toList();
		}

	}

	record SignupRequest(@NotBlank String signupTicket, List<AgreementRequest> agreements,
			TemperatureSensitivity temperatureSensitivity) {
	}

	record RefreshRequest(@NotBlank String refreshToken) {
	}

	record LogoutRequest(String refreshToken) {
	}

	private static final String BEARER = "Bearer ";

	private final AuthService authService;
	private final TermsService termsService;

	AuthController(AuthService authService, TermsService termsService) {
		this.authService = authService;
		this.termsService = termsService;
	}

	/**
	 * @param token 카카오 접근 토큰 / 애플 identity token / 구글 ID 토큰
	 * @param authorizationCode 애플 인가 코드·구글 서버 인가 코드(연결 해제용 자격 획득, 선택)
	 */
	record LoginRequest(@NotNull AuthProvider provider, @NotBlank String token, String authorizationCode) {

		@Override
		public String toString() {
			return "LoginRequest[provider=" + provider + "]";
		}

	}

	enum Platform {

		IOS, ANDROID

	}

	/** 앱에 보여 줄 로그인 수단 (BR-MEM-02). */
	record ProvidersResponse(List<AuthProvider> providers) {
	}

	/** 앱 플랫폼별 로그인 수단 노출 규칙: iOS는 카카오·애플·구글, Android는 카카오·구글(Q58). */
	private static final Map<Platform, List<AuthProvider>> EXPOSED = Map.of(Platform.IOS,
			List.of(AuthProvider.KAKAO, AuthProvider.APPLE, AuthProvider.GOOGLE), Platform.ANDROID,
			List.of(AuthProvider.KAKAO, AuthProvider.GOOGLE));

	/** 소셜 로그인 (FR-MEM-02). 처음 보는 계정이면 registered=false + 가입 티켓. */
	@PostMapping("/api/v1/auth/login")
	LoginResponse login(@Valid @RequestBody LoginRequest request) {
		if (request.provider() == AuthProvider.DEV) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "지원하지 않는 로그인 제공자입니다.");
		}
		return LoginResponse.from(authService
			.login(new ProviderCredential(request.provider(), request.token(), request.authorizationCode(), null)));
	}

	/**
	 * 로그인 수단 목록: 플랫폼 노출 규칙 ∩ 서버에 키가 설정된 제공자. 서버는 플랫폼과 무관하게 세 제공자를 모두 검증할 수 있다(BR-MEM-02).
	 */
	@GetMapping("/api/v1/auth/providers")
	ProvidersResponse providers(@RequestParam Platform platform) {
		List<AuthProvider> available = authService.availableProviders();
		return new ProvidersResponse(EXPOSED.get(platform).stream().filter(available::contains).toList());
	}

	@PostMapping("/api/v1/signup")
	LoginResponse signup(@Valid @RequestBody SignupRequest request) {
		return LoginResponse.from(authService.completeSignup(request.signupTicket(),
				AgreementRequest.toInputs(request.agreements()), request.temperatureSensitivity()));
	}

	@PostMapping("/api/v1/auth/refresh")
	TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return TokenResponse.from(authService.refresh(request.refreshToken()));
	}

	/** 갱신 토큰(본문) 또는 접근 토큰(Authorization 헤더)으로 그 로그인 세션을 폐기한다. */
	@PostMapping("/api/v1/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestBody(required = false) LogoutRequest request) {
		String accessToken = authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
				? authorization.substring(BEARER.length()).trim() : null;
		authService.logout(accessToken, request == null ? null : request.refreshToken());
	}

	/** 현재 시행 중인 약관 (가입·재동의 화면용, 비회원 공개). */
	@GetMapping("/api/v1/terms")
	List<TermsResponse> terms() {
		return TermsResponse.from(termsService.currentTerms());
	}

}
