package kr.weathertalk.neighborhood;

/**
 * 기기가 보낸 좌표와 위치 정확도. 동네 판정에만 쓰고 버린다 — 저장·로그·오류 메시지·이벤트에 남기지 않는다(ADR 0009, BR-NBR-02).
 * {@link #toString()}도 값을 드러내지 않는다(실수로 로그에 찍혀도 좌표가 남지 않게).
 *
 * @param latitude 위도(WGS84). 없을 수 있음(→ 거부 사유 LOCATION_MISSING)
 * @param longitude 경도(WGS84)
 * @param accuracyMeters 위치 정확도(미터, 작을수록 정확). 조회용 판정에서는 선택, 현재 동네 확정에서는 필수
 */
public record DeviceLocation(Double latitude, Double longitude, Double accuracyMeters) {

	@Override
	public String toString() {
		return "DeviceLocation[redacted]";
	}

}
