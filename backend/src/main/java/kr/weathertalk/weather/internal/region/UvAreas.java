package kr.weathertalk.weather.internal.region;

import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 동네 → 생활기상지수 행정구역(areaNo). 자외선은 시군구 단위로 받아 같은 시군구 동네가 한 사본을 함께 쓴다(수집 단위, 03 §2).
 * areaNo는 10자리 행정구역 코드이며 시군구 코드(앞 5자리) + "00000".
 */
public final class UvAreas {

	private static final int SIGUNGU_DIGITS = 5;

	private UvAreas() {
	}

	public static String areaNo(NeighborhoodId neighborhood) {
		String code = neighborhood.value();
		if (code.length() < SIGUNGU_DIGITS) {
			return code;
		}
		return code.substring(0, SIGUNGU_DIGITS) + "0".repeat(Math.max(0, code.length() - SIGUNGU_DIGITS));
	}

}
