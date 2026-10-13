package kr.weathertalk.home;

import kr.weathertalk.feeling.RecentFeelingRecord;

/**
 * 체감 피드백 버튼 상태 (07 §1.2, SCR-01). 가능 여부의 최종 검증은 기록 시점에 feeling이 한다.
 *
 * @param available 보는 동네가 회원의 현재 동네인가(아니면 "현재 동네에서만 답할 수 있어요")
 * @param recentRecord 이번 기록 간격 안의 내 기록(있으면 "다시 답하면 이전 기록을 바꿔요"). 없거나 현재 동네가 아니면 null
 */
public record FeedbackStatus(boolean available, RecentFeelingRecord recentRecord) {
}
