package kr.weathertalk.member.internal.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.idempotency.IdempotencyKey;

/**
 * 현재 요청자 해석 리졸버와 공통 가드(가입 필요·약관 재동의 필요·인증 실패), 요청 식별자 헤더 해석 (contracts.md §2.1, §4).
 */
@WebMvcTest(RequesterArgumentResolverTests.GuardedController.class)
@Import(RequesterArgumentResolverTests.GuardedController.class)
class RequesterArgumentResolverTests {

	static final MemberId MEMBER_ID = new MemberId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MemberApi memberApi;

	@Test
	void guestWithoutAuthorizationHeaderCanRead() throws Exception {
		given(memberApi.resolveRequester(null)).willReturn(Requester.GUEST);

		mvc.perform(get("/test/any")).andExpect(status().isOk()).andExpect(content().string("guest"));
	}

	@Test
	void guestOnMemberOnlyEndpointGetsSignupRequired() throws Exception {
		given(memberApi.resolveRequester(null)).willReturn(Requester.GUEST);

		mvc.perform(post("/test/member"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.SIGNUP_REQUIRED.name()));
	}

	@Test
	void invalidTokenIsAuthenticationFailedNotGuest() throws Exception {
		given(memberApi.resolveRequester("bad"))
			.willThrow(new DomainException(ErrorCode.AUTHENTICATION_FAILED, "invalid"));

		mvc.perform(get("/test/any").header(HttpHeaders.AUTHORIZATION, "Bearer bad"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
	}

	@Test
	void malformedAuthorizationHeaderIsAuthenticationFailed() throws Exception {
		mvc.perform(get("/test/any").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_FAILED.name()));
	}

	@Test
	void memberNeedingTermsReagreementCanUseAllowedWritesButNotBlockedWrites() throws Exception {
		given(memberApi.resolveRequester("t")).willReturn(new Requester.Member(MEMBER_ID, false, true));

		mvc.perform(post("/test/member").header(HttpHeaders.AUTHORIZATION, "Bearer t"))
			.andExpect(status().isOk())
			.andExpect(content().string(MEMBER_ID.value().toString()));
		mvc.perform(post("/test/write").header(HttpHeaders.AUTHORIZATION, "Bearer t"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.TERMS_REAGREEMENT_REQUIRED.name()));
	}

	@Test
	void memberWithAgreedTermsCanWrite() throws Exception {
		given(memberApi.resolveRequester("t")).willReturn(new Requester.Member(MEMBER_ID, false, false));

		mvc.perform(post("/test/write").header(HttpHeaders.AUTHORIZATION, "bearer t")).andExpect(status().isOk());
	}

	@Test
	void idempotencyKeyIsReadFromHeader() throws Exception {
		mvc.perform(post("/test/idempotent").header(IdempotencyKey.HEADER, "app-key-0001"))
			.andExpect(status().isOk())
			.andExpect(content().string("app-key-0001"));
	}

	@Test
	void missingIdempotencyKeyIsInvalidRequest() throws Exception {
		mvc.perform(post("/test/idempotent"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
	}

	@RestController
	static class GuardedController {

		@GetMapping("/test/any")
		String any(Requester requester) {
			return requester instanceof Requester.Member ? "member" : "guest";
		}

		@PostMapping("/test/member")
		String memberOnly(Requester.Member member) {
			return member.memberId().value().toString();
		}

		@PostMapping("/test/write")
		String write(@RequiresAgreedTerms Requester.Member member) {
			return member.memberId().value().toString();
		}

		@PostMapping("/test/idempotent")
		String idempotent(IdempotencyKey key) {
			return key.value();
		}

	}

}
