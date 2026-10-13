package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.MappingLayer;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 민감도별 체감 2단계(체감 매핑) 조회 (BR-FEL-13). 적용 중인 매핑 버전에서 세부 → 계절 없음 → 축약 셀 순으로 찾는다.
 * feeling.mapping.enabled({@link FeelingMappingFlag})가 꺼져 있거나 적용 중인 버전이 없으면 2단계를 건너뛴다.
 * <p>
 * 매핑 버전은 바뀌지 않으므로 적용 중인 버전 번호만 매번 확인하고, 번호가 같으면 메모리의 셀을 그대로 쓴다.
 */
@Component
class FeelingMappingLookup {

	/**
	 * @param mappingVersion 사용한 매핑 버전
	 * @param sampleCount 셀의 근거 표본 수(가중 전)
	 * @param layer 값을 얻은 셀 층
	 */
	record Cell(TemperatureTag temperature, HumidityTag humidity, WindTag wind, int mappingVersion, int sampleCount,
			MappingLayer layer) {
	}

	private final FeelingMappingRepository repository;
	private final FeelingMappingFlag flag;
	private final ReferenceTable referenceTable;
	private final AtomicReference<LoadedMapping> cache = new AtomicReference<>();

	FeelingMappingLookup(FeelingMappingRepository repository, FeelingMappingFlag flag, ReferenceTable referenceTable) {
		this.repository = repository;
		this.flag = flag;
		this.referenceTable = referenceTable;
	}

	/** 쓸 수 있는 매핑: 플래그가 켜져 있고 적용 중인 버전이 있을 때만. */
	Optional<LoadedMapping> active() {
		if (!flag.enabled()) {
			return Optional.empty();
		}
		Optional<Integer> applied = repository.appliedVersion();
		if (applied.isEmpty()) {
			return Optional.empty();
		}
		LoadedMapping cached = cache.get();
		if (cached != null && cached.version() == applied.get()) {
			return Optional.of(cached);
		}
		Optional<LoadedMapping> loaded = repository.load(applied.get());
		loaded.ifPresent(cache::set);
		return loaded;
	}

	Optional<Cell> find(TemperatureSensitivity sensitivity, Instant basisTime, Double temperature, Double humidity,
			Double windSpeed) {
		return active().flatMap(mapping -> mapping.find(sensitivity, basisTime, temperature, humidity, windSpeed)
			.map(c -> new Cell(c.temperature(), c.humidity(), c.wind(), mapping.version(), c.sampleCount(),
					c.key().layer())));
	}

	/**
	 * 그룹의 예상 온도 단계 (FR-FEL-11 체감 경향, BR-FEL-17 규칙 3): 매핑 셀(대체 순서 끝까지)이 있으면 그 값, 없으면 고정 기준표.
	 * 동네 체감은 쓰지 않는다(자기 기록이 섞이므로).
	 *
	 * @param mapping 쓸 매핑. 없으면 기준표만
	 */
	static TemperatureTag expected(Optional<LoadedMapping> mapping, ReferenceTable referenceTable,
			TemperatureSensitivity sensitivity, Instant at, double temperature, Double humidity, Double windSpeed) {
		return mapping.flatMap(m -> m.find(sensitivity, at, temperature, humidity, windSpeed))
			.map(MappingCell::temperature)
			.orElseGet(() -> referenceTable.tagFor(sensitivity, temperature));
	}

	ReferenceTable referenceTable() {
		return referenceTable;
	}

}
