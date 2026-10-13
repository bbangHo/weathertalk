package kr.weathertalk.neighborhood;

/**
 * 동네 경계의 대표 좌표(WGS84). 사람의 위치가 아니다 — 사람의 좌표는 {@link DeviceLocation}으로만 받고 저장하지 않는다.
 */
public record GeoPoint(double latitude, double longitude) {
}
