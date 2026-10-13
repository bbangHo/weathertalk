package kr.weathertalk.member;

import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 현재 요청자 해석 결과: 회원 또는 비회원 (contracts.md §2.1). 인증 실패는 값이 아니라 AUTHENTICATION_FAILED 예외로 끝난다.
 * <p>
 * 컨트롤러는 파라미터로 받는다:
 * <ul>
 * <li>{@code Requester} — 비회원도 허용 (조회)</li>
 * <li>{@code Requester.Member} — 회원 전용. 비회원이면 SIGNUP_REQUIRED</li>
 * <li>{@code @RequiresAgreedTerms Requester.Member} — 약관 재동의 전 막히는 쓰기(contracts.md §4). 재동의 필요면 TERMS_REAGREEMENT_REQUIRED</li>
 * </ul>
 * 서비스 코드에서는 {@link #requireMember()}, {@link #requireWritableMember()}로 같은 판정을 한다.
 */
public sealed interface Requester permits Requester.Member, Requester.Guest {

	Guest GUEST = new Guest();

	/**
	 * @param admin 관리자 여부. 관리자 전용 기능은 이 값으로만 판정한다(contracts.md §4).
	 * @param termsReagreementRequired 필수 약관 새 버전에 아직 동의하지 않았다.
	 */
	record Member(MemberId memberId, boolean admin, boolean termsReagreementRequired) implements Requester {

		/** 약관 재동의 전에 막히는 쓰기(체감 기록·피드백·현장 제보·게시글·댓글 작성·좋아요) 직전에 부른다. */
		public Member requireAgreedTerms() {
			if (termsReagreementRequired) {
				throw new DomainException(ErrorCode.TERMS_REAGREEMENT_REQUIRED, "필수 약관에 다시 동의해야 합니다.");
			}
			return this;
		}

	}

	record Guest() implements Requester {
	}

	default Member requireMember() {
		if (this instanceof Member member) {
			return member;
		}
		throw new DomainException(ErrorCode.SIGNUP_REQUIRED, "회원 가입이 필요한 기능입니다.");
	}

	default Member requireWritableMember() {
		return requireMember().requireAgreedTerms();
	}

}
