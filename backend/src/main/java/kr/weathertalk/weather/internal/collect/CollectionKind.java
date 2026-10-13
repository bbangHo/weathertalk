package kr.weathertalk.weather.internal.collect;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 수집 데이터 종류와 발표 시(時) (BR-WEA-02). 발표 시각표는 기상청·에어코리아 규격이라 운영 파라미터가 아니다.
 */
public enum CollectionKind {

	/** 초단기실황: 매시 정시 */
	OBSERVATION(allHours()),

	/** 단기예보: 02·05·08·11·14·17·20·23시 */
	SHORT_TERM(Set.of(2, 5, 8, 11, 14, 17, 20, 23)),

	/** 중기 육상예보: 06·18시 */
	MID_LAND(Set.of(6, 18)),

	/** 중기 기온: 06·18시 */
	MID_TEMPERATURE(Set.of(6, 18)),

	/** 대기질: 매시 측정 */
	AIR(allHours()),

	/** 자외선(생활기상지수 V4): 06·18시 (03 §9-8 외부 확인 필요) */
	UV(Set.of(6, 18)),

	/** 동네-측정소 대응(발표 시각 없음. 실패 백오프에만 쓴다) */
	AIR_STATIONS(Set.of());

	private final Set<Integer> issueHours;

	CollectionKind(Set<Integer> issueHours) {
		this.issueHours = issueHours;
	}

	public Set<Integer> issueHours() {
		return issueHours;
	}

	private static Set<Integer> allHours() {
		return IntStream.range(0, 24).boxed().collect(Collectors.toUnmodifiableSet());
	}

}
