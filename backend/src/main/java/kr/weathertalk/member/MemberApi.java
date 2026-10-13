package kr.weathertalk.member;

/**
 * member 모듈의 공개 인터페이스. 다른 모듈은 이 타입으로만 member 기능을 사용한다.
 * <p>
 * 제공 기능 목록은 docs/to-be/contracts.md §2.1 참조. 마일스톤별로 메서드를 추가한다.
 */
public interface MemberApi {

	/**
	 * 현재 요청자 해석 (contracts.md §2.1). 컨트롤러는 이 메서드 대신 {@link Requester} 파라미터를 선언한다.
	 *
	 * @param bearerToken 접근 토큰. 없으면(null·빈 값) 비회원이다.
	 * @return 회원 또는 {@link Requester#GUEST}
	 * @throws kr.weathertalk.shared.error.DomainException AUTHENTICATION_FAILED — 토큰이 잘못됐거나 만료·폐기됨. 비회원으로 낮추지 않는다.
	 */
	Requester resolveRequester(String bearerToken);

}
