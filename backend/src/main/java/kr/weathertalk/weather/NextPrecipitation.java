package kr.weathertalk.weather;

import java.time.Instant;

/** 다음 강수 (FR-WEA-02 4단계, BR-WEA-14): 범위 안에서 강수 형태가 있는 가장 이른 칸. */
public record NextPrecipitation(Instant startsAt, PrecipitationType type, String phrase) {
}
