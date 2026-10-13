package kr.weathertalk.neighborhood.internal.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.neighborhood.internal.NeighborhoodRepository;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 비회원에게도 공개된 동네 기능: 좌표로 동네 판정(FR-NBR-01), 검색·단계 선택(FR-NBR-07), 동네 조회.
 */
@RestController
class NeighborhoodController {

	/**
	 * 단계 선택 응답. sido 없음 → 시도 이름 목록, sido만 → 시군구 이름 목록, 둘 다 → 동네 목록.
	 */
	record RegionResponse(List<String> names, List<NeighborhoodResponse> neighborhoods) {
	}

	private final NeighborhoodApi neighborhoodApi;
	private final NeighborhoodRepository repository;

	NeighborhoodController(NeighborhoodApi neighborhoodApi, NeighborhoodRepository repository) {
		this.neighborhoodApi = neighborhoodApi;
		this.repository = repository;
	}

	/** 저장 없는 판정. 어느 동네에도 속하지 않으면 NEIGHBORHOOD_NOT_RESOLVABLE(서버 오류 아님). */
	@PostMapping("/api/v1/neighborhoods/locate")
	NeighborhoodResponse locate(@RequestBody LocationRequest request) {
		return neighborhoodApi.locate(request.toLocation())
			.map(NeighborhoodResponse::from)
			.orElseThrow(() -> new DomainException(ErrorCode.NEIGHBORHOOD_NOT_RESOLVABLE, "이 위치가 속한 동네를 찾지 못했습니다."));
	}

	@GetMapping("/api/v1/neighborhoods/search")
	List<NeighborhoodResponse> search(@RequestParam(name = "q", required = false) String query) {
		return NeighborhoodResponse.from(neighborhoodApi.search(query));
	}

	@GetMapping("/api/v1/neighborhoods/regions")
	RegionResponse regions(@RequestParam(required = false) String sido,
			@RequestParam(required = false) String sigungu) {
		if (sido == null || sido.isBlank()) {
			return new RegionResponse(repository.sidoNames(), List.of());
		}
		if (sigungu == null || sigungu.isBlank()) {
			return new RegionResponse(repository.sigunguNames(sido), List.of());
		}
		return new RegionResponse(List.of(), NeighborhoodResponse.from(repository.inSigungu(sido, sigungu)));
	}

	@GetMapping("/api/v1/neighborhoods/{id}")
	NeighborhoodResponse get(@PathVariable String id) {
		return neighborhoodApi.findNeighborhood(new NeighborhoodId(id))
			.map(NeighborhoodResponse::from)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "없는 동네입니다."));
	}

	@GetMapping("/api/v1/neighborhoods")
	List<NeighborhoodResponse> getAll(@RequestParam List<String> ids) {
		return NeighborhoodResponse.from(List.copyOf(neighborhoodApi
			.findNeighborhoods(ids.stream().map(NeighborhoodId::new).toList())
			.values()));
	}

}
