package kr.weathertalk.feeling.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.MemberWithdrawn;

/**
 * 회원탈퇴함 구독 (FR-FEL-09, 04 §6.4): 그 회원의 모든 체감 기록에서 회원 ID만 비운다. 태그·동네·시각·기록 시점 민감도·관측 스냅샷·
 * 출처·피드백 정보는 익명으로 남아 동네 체감 집계에 계속 쓰인다(ADR 0010). 다시 전달돼도 결과가 같다(멱등).
 * 민감도 보정 제안 무효화는 보정 제안과 함께 M6에서 더한다.
 */
@Component
class MemberWithdrawalAnonymizer {

	private final FeelingRecordRepository records;

	MemberWithdrawalAnonymizer(FeelingRecordRepository records) {
		this.records = records;
	}

	@ApplicationModuleListener
	void on(MemberWithdrawn event) {
		records.anonymize(event.memberId().value());
	}

}
