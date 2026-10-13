package kr.weathertalk.weather.internal.fieldreport;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.MemberWithdrawn;

/**
 * 회원탈퇴함 구독 (03 §6.4, FR-WEA-10): 그 회원의 현장 제보에서 회원 ID만 비운다. 제보는 보존 기간 동안 익명 제보로 남는다.
 * 다시 전달돼도 결과가 같다(멱등).
 */
@Component
class FieldReportWithdrawalAnonymizer {

	private final FieldReportRepository reports;

	FieldReportWithdrawalAnonymizer(FieldReportRepository reports) {
		this.reports = reports;
	}

	@ApplicationModuleListener
	void on(MemberWithdrawn event) {
		reports.anonymize(event.memberId().value());
	}

}
