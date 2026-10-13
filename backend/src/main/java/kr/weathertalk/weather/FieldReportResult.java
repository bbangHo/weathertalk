package kr.weathertalk.weather;

/** 현장 제보 하기·취소 결과 (FR-WEA-04·14): 저장(취소)된 제보와 그 동네의 갱신된 현황. */
public record FieldReportResult(FieldReport report, FieldReportStatus status) {
}
