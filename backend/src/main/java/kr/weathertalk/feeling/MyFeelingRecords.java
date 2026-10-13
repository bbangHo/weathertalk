package kr.weathertalk.feeling;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 회원의 체감 기록 목록 + 내 체감 패턴 (FR-FEL-11, 마이페이지). 대상은 기록 시각이 최근 feeling.my-records.period 안인
 * 본인 기록(모든 동네), 기록 시각 내림차순.
 */
public record MyFeelingRecords(List<Item> records, Pattern pattern) {

	/** @param neighborhoodName "시군구 읍면동". 동네를 찾지 못하면 null */
	public record Item(FeelingRecordId id, Instant recordedAt, NeighborhoodId neighborhoodId, String neighborhoodName,
			TemperatureTag temperatureTag, HumidityTag humidityTag, WindTag windTag, FeelingRecordSource source,
			TemperatureSensitivity temperatureSensitivity) {
	}

	/**
	 * 내 체감 패턴. 보여주기만 하며 민감도 변경 권유는 민감도 보정 제안만 한다.
	 *
	 * @param temperatureDistribution 온도 태그 단계별 건수(모든 단계, 단계 순)
	 * @param averageDifference (기록 온도 단계 − 기록 시점 그룹의 예상 단계) 평균. 기록 부족이면 null
	 * @param tendencySampleCount 경향 계산에 쓸 수 있는 기록 수(관측 스냅샷 '확보'만)
	 */
	public record Pattern(int recordCount, Map<TemperatureTag, Integer> temperatureDistribution, Tendency tendency,
			String tendencyPhrase, Double averageDifference, int tendencySampleCount) {
	}

	public enum Tendency {

		/** 같은 그룹보다 춥게 느끼는 편 */
		FEELS_COLDER,

		/** 비슷하게 느끼는 편 */
		SIMILAR,

		/** 같은 그룹보다 덥게 느끼는 편 */
		FEELS_HOTTER,

		/** 경향을 말하기엔 기록 부족 */
		INSUFFICIENT_RECORDS

	}

}
