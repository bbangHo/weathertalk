package kr.weathertalk.feeling.internal.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.feeling.CalibrationOffer;
import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.internal.FeelingCalibrationService;
import kr.weathertalk.member.Requester;

/**
 * 민감도 보정 제안 웹 API (FR-FEL-08). 회원 전용. 민감도 변경·응답은 약관 재동의 전에도 허용되는 쓰기라 재동의 가드를 두지 않는다
 * (contracts.md §4). 수락·거절은 상태로 멱등하다(같은 응답을 다시 보내도 결과가 같다).
 */
@RestController
class CalibrationOfferController {

	/** @param offer 유효한 제안이 없으면 null */
	record CalibrationOfferResponse(CalibrationOffer offer) {
	}

	private final FeelingApi feelingApi;
	private final FeelingCalibrationService calibration;

	CalibrationOfferController(FeelingApi feelingApi, FeelingCalibrationService calibration) {
		this.feelingApi = feelingApi;
		this.calibration = calibration;
	}

	/** 조회(마이페이지): 유효한 '제안됨' 제안. 홈은 홈 조합의 보정 제안 카드 섹션으로 받는다. */
	@GetMapping("/api/v1/members/me/calibration-offer")
	CalibrationOfferResponse find(Requester.Member member) {
		return new CalibrationOfferResponse(feelingApi.findCalibrationOffer(member.memberId()).orElse(null));
	}

	/** 수락: feeling이 member 온도 민감도 변경(출처 = 보정 제안 수락)을 부른 뒤 '수락됨'으로 바꾼다. */
	@PostMapping("/api/v1/members/me/calibration-offers/{offerId}/accept")
	CalibrationOffer accept(Requester.Member member, @PathVariable UUID offerId) {
		return calibration.accept(member.memberId(), offerId);
	}

	/** 거절: '거절됨'으로 바꾸고 거절 후 대기를 시작한다. */
	@PostMapping("/api/v1/members/me/calibration-offers/{offerId}/decline")
	CalibrationOffer decline(Requester.Member member, @PathVariable UUID offerId) {
		return feelingApi.declineCalibrationOffer(member.memberId(), offerId);
	}

}
