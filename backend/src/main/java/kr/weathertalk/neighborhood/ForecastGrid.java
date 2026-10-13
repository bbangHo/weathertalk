package kr.weathertalk.neighborhood;

/**
 * 기상청 단기예보 격자 (CONTEXT.md 예보 격자). 동네마다 정확히 하나(BR-NBR-04).
 */
public record ForecastGrid(int nx, int ny) {
}
