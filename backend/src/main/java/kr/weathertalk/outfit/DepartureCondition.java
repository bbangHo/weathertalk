package kr.weathertalk.outfit;

import java.time.Instant;

/**
 * 외출 시각 하나의 조건 (FR-OUT-02 입력).
 *
 * @param temperatureLevel 그 시각의 온도 체감 태그 단계(1~7)
 * @param precipitation null이면 없음
 */
public record DepartureCondition(Instant departureAt, int temperatureLevel, Precipitation precipitation) {
}
