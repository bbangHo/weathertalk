package kr.weathertalk.home;

import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 홈의 내 그룹 (07 §1.3, Q6·Q59 확정). 회원은 회원 민감도(기기가 보낸 값은 무시), 비회원은 기기에서 고른 민감도.
 * 비회원이 고르지 않았으면 강조 없이 '보통' 기준으로 옷 추천·어제 비교를 보여주고 "보통 기준"·"내 민감도 고르기" 안내를 단다.
 *
 * @param highlightedGroup 강조할 카드. 고르지 않은 비회원은 null
 * @param basisGroup 옷 추천·어제 비교·외출 시각 기준 그룹
 * @param selectionSuggested 기기 민감도를 고르지 않아 '보통' 기준으로 보여준다 — 앱은 '내 민감도 고르기' 안내를 띄운다
 */
public record PersonalBasis(TemperatureSensitivity highlightedGroup, TemperatureSensitivity basisGroup,
		boolean selectionSuggested) {
}
