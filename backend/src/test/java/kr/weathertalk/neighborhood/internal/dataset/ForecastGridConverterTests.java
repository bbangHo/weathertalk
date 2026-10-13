package kr.weathertalk.neighborhood.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import kr.weathertalk.neighborhood.ForecastGrid;

/**
 * 위경도 → 기상청 단기예보 격자 (BR-NBR-04, 레거시 spec/04 BR-LOC-05 상수).
 * 기대값은 기상청 '동네예보 격자 위치' 표에 실린 대표 지점의 격자다.
 */
class ForecastGridConverterTests {

	@ParameterizedTest(name = "{0}: ({1}, {2}) -> ({3}, {4})")
	@CsvSource({
			"기준점(126E 38N), 38.0, 126.0, 43, 136",
			"서울 종로구 청운효자동, 37.5841, 126.9706, 60, 127",
			"서울 중구 명동, 37.5609, 126.9863, 60, 127",
			"부산 중구 중앙동, 35.1047, 129.0365, 97, 74",
			"대전 서구 둔산1동, 36.3510, 127.3790, 67, 100",
			"제주 제주시 일도1동, 33.5112, 126.5297, 53, 38" })
	void convertsKnownPoints(String name, double lat, double lon, int nx, int ny) {
		assertThat(ForecastGridConverter.toGrid(lat, lon)).isEqualTo(new ForecastGrid(nx, ny));
	}

}
