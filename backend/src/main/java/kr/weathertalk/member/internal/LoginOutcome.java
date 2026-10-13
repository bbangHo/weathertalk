package kr.weathertalk.member.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.member.internal.auth.IssuedTokens;
import kr.weathertalk.member.internal.domain.Terms;

/**
 * 로그인 결과 (FR-MEM-02 결과). 기존 회원이면 토큰, 처음 보는 로그인 계정이면 가입 티켓 — 회원은 만들지 않는다.
 */
public sealed interface LoginOutcome {

	record Registered(IssuedTokens tokens, MemberSummary member) implements LoginOutcome {
	}

	record SignupRequired(String signupTicket, Instant signupTicketExpiresAt, List<Terms> terms)
			implements LoginOutcome {

		@Override
		public String toString() {
			return "SignupRequired[signupTicketExpiresAt=" + signupTicketExpiresAt + "]";
		}

	}

	/** 회원 요약: 앱의 온보딩·재동의 분기용. */
	record MemberSummary(UUID memberId, String nickname, TemperatureSensitivity temperatureSensitivity,
			List<Terms> termsToReagree) {

		public boolean termsReagreementRequired() {
			return !termsToReagree.isEmpty();
		}

	}

}
