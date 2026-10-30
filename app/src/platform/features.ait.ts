// 앱인토스(토스 미니앱) 빌드에서 켜고 끄는 기능.

/** 앱인토스에서는 FCM을 쓸 수 없다. 토스 스마트 발송 연동 전까지 알림 구독을 숨긴다(docs/plan/apps-in-toss.md '사업자 없이 출시'). */
export const PUSH_NOTIFICATIONS_ENABLED = false;
