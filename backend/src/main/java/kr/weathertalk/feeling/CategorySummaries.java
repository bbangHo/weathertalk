package kr.weathertalk.feeling;

/** 범주별(온도·습도·바람) 동네 체감 집계. */
public record CategorySummaries(CategorySummary<TemperatureTag> temperature, CategorySummary<HumidityTag> humidity,
		CategorySummary<WindTag> wind) {
}
