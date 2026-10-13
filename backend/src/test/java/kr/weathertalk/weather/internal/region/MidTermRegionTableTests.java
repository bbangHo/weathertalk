package kr.weathertalk.weather.internal.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import kr.weathertalk.neighborhood.ForecastGrid;
import kr.weathertalk.neighborhood.GeoPoint;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodStatus;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 행정동 → 중기 예보구역 대응표 (FR-WEA-03, 03 §6.2). 저장소의 {@value MidTermRegions#TABLE}이 생성기 결과와 같은지 검증하고,
 * 매칭 요약(유형별 수·애매한 이름·폴백 목록)을 출력한다.
 * <p>
 * 표 다시 만들기: 환경 변수 {@code REGENERATE_MID_TERM_REGIONS=true}로 이 테스트를 실행하면 파일을 덮어쓴다.
 *
 * <pre>
 * REGENERATE_MID_TERM_REGIONS=true ./gradlew test --tests '*MidTermRegionTableTests'
 * </pre>
 */
class MidTermRegionTableTests {

	static final Path SOURCE = Path.of("src/main/resources", MidTermRegions.TABLE);

	@Test
	void committedTableEqualsGeneratedTable() throws IOException {
		MidTermRegionTableGenerator.Result result = MidTermRegionTableGenerator.generate();
		printSummary(result);
		if ("true".equalsIgnoreCase(System.getenv("REGENERATE_MID_TERM_REGIONS"))) {
			Files.writeString(SOURCE, result.csv(), StandardCharsets.UTF_8);
		}
		String committed = new ClassPathResource(MidTermRegions.TABLE).getContentAsString(StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		assertThat(committed).as("대응표가 생성 결과와 다르면 REGENERATE_MID_TERM_REGIONS=true로 다시 생성").isEqualTo(result.csv());
		assertThat(result.rows()).hasSize(3564);
	}

	@ParameterizedTest(name = "{1} {2} {3} → {4}·{5}")
	@CsvSource({
			"1111051500, 서울특별시, 종로구, 청운효자동, 11B00000, 11B10101",
			"4111156000, 경기도, 수원시장안구, 파장동, 11B00000, 11B20601",
			"4161051000, 경기도, 광주시, 경안동, 11B00000, 11B20702",
			"4311325000, 충청북도, 청주시흥덕구, 오송읍, 11C10000, 11C10301",
			"4711155000, 경상북도, 포항시남구, 송도동, 11H10000, 11H10201",
			"5115051000, 강원특별자치도, 강릉시, 홍제동, 11D20000, 11D20501",
			"5111025000, 강원특별자치도, 춘천시, 신북읍, 11D10000, 11D10301",
			"5182025000, 강원특별자치도, 고성군, 간성읍, 11D20000, 11D20402",
			"4882025000, 경상남도, 고성군, 고성읍, 11H20000, 11H20404",
			"3611051000, 세종특별자치시, 세종특별자치시, 한솔동, 11C20000, 11C20404",
			"4794025000, 경상북도, 울릉군, 울릉읍, 11H10000, 11E00101",
			"2872033000, 인천광역시, 옹진군, 백령면, 11B00000, 11A00101" })
	void knownNeighborhoods(String code, String sido, String sigungu, String eupmyeondong, String land,
			String temperature) {
		MidTermRegions regions = new MidTermRegions();
		Neighborhood neighborhood = new Neighborhood(new NeighborhoodId(code), sido, sigungu, eupmyeondong,
				new GeoPoint(37, 127), new ForecastGrid(60, 127), NeighborhoodStatus.VALID);

		assertThat(regions.find(neighborhood)).contains(new MidTermRegions.MidTermRegion(land, temperature));
	}

	/** 표에 없는 코드(기준 데이터가 더 새 버전): 같은 시군구 → 시도 대표 지점 순으로 대응한다. */
	@Test
	void codesMissingFromTableFallBackToSigunguThenSido() {
		MidTermRegions regions = new MidTermRegions();

		assertThat(regions.find(neighborhood("1111099999", "서울특별시"))).contains(new MidTermRegions.MidTermRegion("11B00000", "11B10101"));
		assertThat(regions.find(neighborhood("5115099999", "강원특별자치도"))).contains(new MidTermRegions.MidTermRegion("11D20000", "11D20501"));
		assertThat(regions.find(neighborhood("9900000007", "샘플도"))).contains(new MidTermRegions.MidTermRegion("11B00000", "11B10101"));
		assertThat(regions.find(neighborhood("8800000000", "없는도"))).isEmpty();
	}

	@Test
	void everyCodeInTableIsInOfficialCodeTableWithMatchingKind() {
		MidTermRegions regions = new MidTermRegions();
		Map<String, String> kinds = MidTermRegionTableGenerator.officialKinds();

		assertThat(regions.landRegionIds()).allSatisfy(id -> assertThat(kinds.get(id)).as(id).isEqualTo("A"));
		assertThat(regions.temperatureRegionIds()).allSatisfy(id -> assertThat(kinds.get(id)).as(id).isEqualTo("C"));
		assertThat(regions.landRegionIds()).containsExactlyInAnyOrder("11B00000", "11C10000", "11C20000", "11D10000",
				"11D20000", "11F10000", "11F20000", "11G00000", "11H10000", "11H20000");
	}

	private static Neighborhood neighborhood(String code, String sido) {
		return new Neighborhood(new NeighborhoodId(code), sido, "어딘가", "어느동", new GeoPoint(37, 127),
				new ForecastGrid(60, 127), NeighborhoodStatus.VALID);
	}

	private static void printSummary(MidTermRegionTableGenerator.Result result) {
		Map<MidTermRegionTableGenerator.Match, Long> counts = result.rows()
			.stream()
			.collect(Collectors.groupingBy(MidTermRegionTableGenerator.Row::match, Collectors.counting()));
		System.out.println("중기 예보구역 대응표: 행정동 " + result.rows().size() + "개, 매칭 유형별 " + counts);
		result.sigunguByMatch().forEach((match, names) -> {
			if (match != MidTermRegionTableGenerator.Match.NAME) {
				System.out.println("  [" + match + "] " + names);
			}
		});
		System.out.println("  시군구 이름 매칭 " + result.sigunguByMatch().get(MidTermRegionTableGenerator.Match.NAME).size()
				+ "개 시군구");
		System.out.println("  같은 이름 지점이 여럿(첫 번째 사용): " + result.ambiguous());
		System.out.println("  쓰이지 않는 기온 지점 " + result.unusedTemperaturePoints().size() + "개: "
				+ result.unusedTemperaturePoints());
	}

}
