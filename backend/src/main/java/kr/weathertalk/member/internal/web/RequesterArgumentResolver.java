package kr.weathertalk.member.internal.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 컨트롤러 파라미터 {@link Requester} / {@link Requester.Member}를 Authorization 헤더로 채운다 (contracts.md §2.1 현재 요청자 해석).
 * <p>
 * 필터 대신 리졸버로 둔 이유: 비회원도 쓰는 조회 API가 대부분이라 요청자가 필요한 핸들러에서만 해석하면 되고,
 * 회원 전용·약관 재동의 판정을 파라미터 타입으로 선언할 수 있어 모듈마다 가드를 다시 쓰지 않는다.
 * 헤더가 있는데 형식이 틀리거나 토큰이 무효면 비회원으로 낮추지 않고 AUTHENTICATION_FAILED를 낸다.
 */
class RequesterArgumentResolver implements HandlerMethodArgumentResolver {

	private static final String BEARER = "Bearer ";
	private static final String CACHE_ATTRIBUTE = RequesterArgumentResolver.class.getName() + ".requester";

	private final MemberApi memberApi;

	RequesterArgumentResolver(MemberApi memberApi) {
		this.memberApi = memberApi;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		return type == Requester.class || type == Requester.Member.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Requester requester = resolve(webRequest);
		if (parameter.getParameterType() == Requester.class) {
			return requester;
		}
		Requester.Member member = requester.requireMember();
		return parameter.hasParameterAnnotation(RequiresAgreedTerms.class) ? member.requireAgreedTerms() : member;
	}

	private Requester resolve(NativeWebRequest webRequest) {
		Object cached = webRequest.getAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (cached instanceof Requester requester) {
			return requester;
		}
		Requester requester = memberApi.resolveRequester(bearerToken(webRequest.getHeader(HttpHeaders.AUTHORIZATION)));
		webRequest.setAttribute(CACHE_ATTRIBUTE, requester, RequestAttributes.SCOPE_REQUEST);
		return requester;
	}

	private static String bearerToken(String authorization) {
		if (authorization == null) {
			return null;
		}
		if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
				|| authorization.substring(BEARER.length()).isBlank()) {
			throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "Authorization 헤더는 'Bearer <토큰>' 형식이어야 합니다.");
		}
		return authorization.substring(BEARER.length()).trim();
	}

}
