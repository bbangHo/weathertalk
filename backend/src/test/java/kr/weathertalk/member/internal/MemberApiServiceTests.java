package kr.weathertalk.member.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import kr.weathertalk.member.Requester;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/** M0 임시 구현: 토큰 없으면 비회원, 있으면 인증 실패 (M1에서 토큰 검증으로 교체). */
class MemberApiServiceTests {

	MemberApiService service = new MemberApiService();

	@Test
	void noTokenIsGuest() {
		assertThat(service.resolveRequester(null)).isEqualTo(Requester.GUEST);
		assertThat(service.resolveRequester(" ")).isEqualTo(Requester.GUEST);
	}

	@Test
	void anyTokenIsAuthenticationFailedUntilM1() {
		assertThatThrownBy(() -> service.resolveRequester("token"))
			.isInstanceOfSatisfying(DomainException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED));
	}

}
