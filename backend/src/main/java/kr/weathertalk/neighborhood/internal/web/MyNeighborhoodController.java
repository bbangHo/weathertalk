package kr.weathertalk.neighborhood.internal.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.member.Requester;
import kr.weathertalk.neighborhood.CurrentNeighborhood;
import kr.weathertalk.neighborhood.InterestMergeResult;
import kr.weathertalk.neighborhood.internal.CurrentNeighborhoodService;
import kr.weathertalk.neighborhood.internal.InterestNeighborhoodService;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 회원의 현재 동네(FR-NBR-02·04)와 관심 동네(FR-NBR-05·06). 비회원은 같은 정보를 기기에 보관하므로 회원 전용이다.
 */
@RestController
class MyNeighborhoodController {

	/** @param neighborhood 현재 동네가 없으면 null — 정상 상태다(BR-NBR-10, legacy 01-#10). */
	record CurrentNeighborhoodResponse(NeighborhoodResponse neighborhood, Instant confirmedAt) {

		static CurrentNeighborhoodResponse from(CurrentNeighborhood current) {
			return new CurrentNeighborhoodResponse(NeighborhoodResponse.from(current.neighborhood()),
					current.confirmedAt());
		}

	}

	record NeighborhoodIdRequest(String neighborhoodId) {
	}

	record NeighborhoodIdsRequest(List<String> neighborhoodIds) {

		List<NeighborhoodId> ids() {
			return neighborhoodIds == null ? List.of() : neighborhoodIds.stream().map(NeighborhoodId::new).toList();
		}

	}

	record MergeResponse(List<NeighborhoodResponse> interests, List<String> skipped, List<String> discarded) {
	}

	private final CurrentNeighborhoodService currentService;
	private final InterestNeighborhoodService interestService;

	MyNeighborhoodController(CurrentNeighborhoodService currentService, InterestNeighborhoodService interestService) {
		this.currentService = currentService;
		this.interestService = interestService;
	}

	/**
	 * 사용자가 확인한 동네로 현재 동네 변경. 좌표 대신 앱이 기기 안에서 판정한 동네 ID를 받는다(ADR 0009).
	 * 없는 동네·폐지된 동네는 CURRENT_NEIGHBORHOOD_UNVERIFIED.
	 */
	@PutMapping("/api/v1/members/me/current-neighborhood")
	CurrentNeighborhoodResponse changeCurrent(Requester.Member member, @RequestBody NeighborhoodIdRequest request) {
		currentService.confirm(member.memberId(), new NeighborhoodId(request.neighborhoodId())).orElseThrow();
		return currentService.find(member.memberId()).map(CurrentNeighborhoodResponse::from).orElseThrow();
	}

	@GetMapping("/api/v1/members/me/current-neighborhood")
	CurrentNeighborhoodResponse current(Requester.Member member) {
		return currentService.find(member.memberId())
			.map(CurrentNeighborhoodResponse::from)
			.orElse(new CurrentNeighborhoodResponse(null, null));
	}

	@GetMapping("/api/v1/members/me/interest-neighborhoods")
	List<NeighborhoodResponse> interests(Requester.Member member) {
		return NeighborhoodResponse.from(interestService.list(member.memberId()));
	}

	@PostMapping("/api/v1/members/me/interest-neighborhoods")
	List<NeighborhoodResponse> addInterest(Requester.Member member, @RequestBody NeighborhoodIdRequest request) {
		return NeighborhoodResponse
			.from(interestService.add(member.memberId(), new NeighborhoodId(request.neighborhoodId())));
	}

	@DeleteMapping("/api/v1/members/me/interest-neighborhoods/{neighborhoodId}")
	List<NeighborhoodResponse> removeInterest(Requester.Member member, @PathVariable String neighborhoodId) {
		return NeighborhoodResponse.from(interestService.remove(member.memberId(), new NeighborhoodId(neighborhoodId)));
	}

	@PutMapping("/api/v1/members/me/interest-neighborhoods/order")
	List<NeighborhoodResponse> reorderInterests(Requester.Member member, @RequestBody NeighborhoodIdsRequest request) {
		return NeighborhoodResponse.from(interestService.reorder(member.memberId(), request.ids()));
	}

	/** 가입·로그인 직후 기기 보관 관심 동네 이전 (FR-NBR-06). */
	@PostMapping("/api/v1/members/me/interest-neighborhoods/merge")
	MergeResponse mergeInterests(Requester.Member member, @RequestBody NeighborhoodIdsRequest request) {
		InterestMergeResult result = interestService.merge(member.memberId(), request.ids());
		return new MergeResponse(NeighborhoodResponse.from(result.interests()),
				result.skipped().stream().map(NeighborhoodId::value).toList(),
				result.discarded().stream().map(NeighborhoodId::value).toList());
	}

}
