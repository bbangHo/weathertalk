package kr.weathertalk.member.internal;

import org.springframework.stereotype.Service;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.Requester;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * {@link MemberApi} 구현.
 * <p>
 * M0 임시: 토큰 발급이 아직 없으므로 토큰이 없으면 비회원, 있으면 모두 인증 실패로 본다. M1에서 접근 토큰 검증으로 바꾼다(FR-MEM-08).
 */
@Service
class MemberApiService implements MemberApi {

	@Override
	public Requester resolveRequester(String bearerToken) {
		if (bearerToken == null || bearerToken.isBlank()) {
			return Requester.GUEST;
		}
		throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "유효하지 않은 접근 토큰입니다.");
	}

}
