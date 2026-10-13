package kr.weathertalk.home;

/**
 * 보는 동네 (07 §1.2). 이름은 항상 조회 대상 동네 자신의 이름이다 — 회원 동네 이름을 쓰지 않는다(legacy 04-#18).
 *
 * @param current 요청자(회원)의 현재 동네인가. 비회원은 항상 false(기기 보관 현재 동네 여부는 앱이 안다)
 */
public record ViewingNeighborhood(String id, String sidoName, String sigunguName, String eupmyeondongName,
		String fullName, String shortName, boolean current) {
}
