package kr.weathertalk.neighborhood.internal.web;

import kr.weathertalk.neighborhood.DeviceLocation;

/**
 * 기기 좌표 요청 본문. 좌표는 URL(쿼리)이 아니라 본문으로만 받는다 — 접근 로그·프록시 로그에 남지 않게(ADR 0009).
 */
record LocationRequest(Double latitude, Double longitude, Double accuracyMeters) {

	DeviceLocation toLocation() {
		return new DeviceLocation(latitude, longitude, accuracyMeters);
	}

	@Override
	public String toString() {
		return "LocationRequest[redacted]";
	}

}
