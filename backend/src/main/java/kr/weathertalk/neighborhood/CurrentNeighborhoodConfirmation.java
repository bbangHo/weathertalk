package kr.weathertalk.neighborhood;

import java.util.Map;

import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 현재 동네 확정 결과 (FR-NBR-03): 확정된 동네 또는 거부 사유.
 * 호출 모듈은 {@link #orElseThrow()}로 받아 그 동네에 기록·작성한다.
 */
public sealed interface CurrentNeighborhoodConfirmation {

	record Confirmed(Neighborhood neighborhood) implements CurrentNeighborhoodConfirmation {
	}

	record Rejected(LocationRejection reason) implements CurrentNeighborhoodConfirmation {
	}

	/**
	 * @throws DomainException CURRENT_NEIGHBORHOOD_UNVERIFIED (응답 속성 {@code reason}에 거부 사유)
	 */
	default Neighborhood orElseThrow() {
		return switch (this) {
			case Confirmed confirmed -> confirmed.neighborhood();
			case Rejected rejected -> throw new DomainException(ErrorCode.CURRENT_NEIGHBORHOOD_UNVERIFIED,
					"현재 동네를 확인하지 못했습니다. 동네를 다시 확인해 주세요.", Map.of("reason", rejected.reason().name()));
		};
	}

}
