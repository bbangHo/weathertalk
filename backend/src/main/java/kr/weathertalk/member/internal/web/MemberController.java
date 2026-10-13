package kr.weathertalk.member.internal.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.SensitivityChangeSource;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.MemberAccountService;
import kr.weathertalk.member.internal.TermsService;
import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.member.internal.web.AuthController.AgreementRequest;
import kr.weathertalk.member.internal.web.LoginResponse.TermsResponse;

/**
 * 마이페이지 (FR-MEM-06·07·08·09·10·11·12). 모두 회원 전용이며, 약관 재동의 전에도 허용되는 기능이라
 * {@code @RequiresAgreedTerms}를 붙이지 않는다(contracts.md §4: 민감도 변경·탈퇴·조회 허용).
 */
@RestController
class MemberController {

	record MyProfileResponse(UUID memberId, String nickname, TemperatureSensitivity temperatureSensitivity,
			String email, List<AuthProvider> providers, Instant nicknameChangeAvailableAt,
			boolean termsReagreementRequired, List<TermsResponse> termsToReagree) {

		static MyProfileResponse from(MemberAccountService.MyProfile p) {
			return new MyProfileResponse(p.memberId(), p.nickname(), p.temperatureSensitivity(), p.email(),
					p.providers(), p.nicknameChangeAvailableAt(), !p.termsToReagree().isEmpty(),
					TermsResponse.from(p.termsToReagree()));
		}

	}

	record ChangeSensitivityRequest(@NotNull TemperatureSensitivity temperatureSensitivity,
			SensitivityChangeSource source) {
	}

	record ChangeNicknameRequest(String nickname) {
	}

	record ReagreeRequest(List<AgreementRequest> agreements) {
	}

	record LinkAccountRequest(@NotNull AuthProvider provider, @NotBlank String token, String authorizationCode) {

		@Override
		public String toString() {
			return "LinkAccountRequest[provider=" + provider + "]";
		}

	}

	private final MemberAccountService accountService;
	private final MemberApi memberApi;
	private final TermsService termsService;

	MemberController(MemberAccountService accountService, MemberApi memberApi, TermsService termsService) {
		this.accountService = accountService;
		this.memberApi = memberApi;
		this.termsService = termsService;
	}

	@GetMapping("/api/v1/members/me")
	MyProfileResponse me(Requester.Member member) {
		return MyProfileResponse.from(accountService.myProfile(member.memberId().value()));
	}

	@PutMapping("/api/v1/members/me/temperature-sensitivity")
	MyProfileResponse changeSensitivity(Requester.Member member, @Valid @RequestBody ChangeSensitivityRequest request) {
		memberApi.changeTemperatureSensitivity(member.memberId(), request.temperatureSensitivity(),
				request.source() == null ? SensitivityChangeSource.DIRECT : request.source());
		return MyProfileResponse.from(accountService.myProfile(member.memberId().value()));
	}

	@PutMapping("/api/v1/members/me/nickname")
	MemberAccountService.NicknameChanged changeNickname(Requester.Member member,
			@RequestBody ChangeNicknameRequest request) {
		return accountService.changeNickname(member.memberId().value(), request.nickname());
	}

	@PostMapping("/api/v1/members/me/terms-agreements")
	MyProfileResponse reagree(Requester.Member member, @RequestBody ReagreeRequest request) {
		termsService.reagree(member.memberId().value(), AgreementRequest.toInputs(request.agreements()));
		return MyProfileResponse.from(accountService.myProfile(member.memberId().value()));
	}

	/** 로그인 계정 연결 (FR-MEM-06): 다른 제공자 계정을 이 회원에 더한다. */
	@PostMapping("/api/v1/members/me/login-accounts")
	MyProfileResponse linkAccount(Requester.Member member, @Valid @RequestBody LinkAccountRequest request) {
		return MyProfileResponse.from(accountService.linkAccount(member.memberId().value(),
				new ProviderCredential(request.provider(), request.token(), request.authorizationCode(), null)));
	}

	/** 로그인 계정 해제 (FR-MEM-07). 마지막 계정은 거부. 제공자 측 연결 해제는 커밋 후 비동기. */
	@DeleteMapping("/api/v1/members/me/login-accounts/{provider}")
	MyProfileResponse unlinkAccount(Requester.Member member, @PathVariable AuthProvider provider) {
		return MyProfileResponse.from(accountService.unlinkAccount(member.memberId().value(), provider));
	}

	@DeleteMapping("/api/v1/members/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void withdraw(Requester.Member member) {
		accountService.withdraw(member.memberId().value());
	}

}
