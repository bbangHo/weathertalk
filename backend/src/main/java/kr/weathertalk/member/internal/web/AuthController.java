package kr.weathertalk.member.internal.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.AuthService;
import kr.weathertalk.member.internal.TermsService;
import kr.weathertalk.member.internal.web.LoginResponse.TermsResponse;
import kr.weathertalk.member.internal.web.LoginResponse.TokenResponse;

/**
 * 가입 완료·토큰 재발급·로그아웃·약관 목록 (FR-MEM-03·04·05). 로그인 진입점은 제공자별(지금은 개발 전용 로그인)로 따로 둔다.
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
