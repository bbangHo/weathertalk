package kr.weathertalk.member.internal.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.LoginOutcome;
import kr.weathertalk.member.internal.auth.IssuedTokens;
import kr.weathertalk.member.internal.domain.Terms;

/**
 * 로그인·가입 완료 응답 (FR-MEM-02·03 결과).
 *
 * @param registered 가입을 마친 회원인지. 앱은 이 불리언으로만 온보딩을 분기한다(legacy 01-#11).
 *                   true면 tokens·member, false면 signupTicket·terms가 채워진다.
 */
public record LoginResponse(boolean registered, TokenResponse tokens, MemberSummaryResponse member,
		SignupTicketResponse signupTicket, List<TermsResponse> terms) {

	public record TokenResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
			Instant refreshTokenExpiresAt) {

		public static TokenResponse from(IssuedTokens tokens) {
			return new TokenResponse(tokens.accessToken(), tokens.accessTokenExpiresAt(), tokens.refreshToken(),
					tokens.refreshTokenExpiresAt());
		}

	}

	public record MemberSummaryResponse(UUID memberId, String nickname, TemperatureSensitivity temperatureSensitivity,
			boolean termsReagreementRequired, List<TermsResponse> termsToReagree) {
	}

	public record SignupTicketResponse(String ticket, Instant expiresAt) {
	}

	public record TermsResponse(String kind, int version, String title, boolean required, Instant effectiveAt,
			String contentUrl) {

		public static TermsResponse from(Terms terms) {
			return new TermsResponse(terms.kind(), terms.version(), terms.title(), terms.required(),
					terms.effectiveAt(), terms.contentUrl());
		}

		public static List<TermsResponse> from(List<Terms> terms) {
			return terms.stream().map(TermsResponse::from).toList();
		}

	}

	public static LoginResponse from(LoginOutcome outcome) {
		return switch (outcome) {
			case LoginOutcome.Registered registered -> {
				LoginOutcome.MemberSummary m = registered.member();
				yield new LoginResponse(true, TokenResponse.from(registered.tokens()),
						new MemberSummaryResponse(m.memberId(), m.nickname(), m.temperatureSensitivity(),
								m.termsReagreementRequired(), TermsResponse.from(m.termsToReagree())),
						null, null);
			}
			case LoginOutcome.SignupRequired signup -> new LoginResponse(false, null, null,
					new SignupTicketResponse(signup.signupTicket(), signup.signupTicketExpiresAt()),
					TermsResponse.from(signup.terms()));
		};
	}

}
