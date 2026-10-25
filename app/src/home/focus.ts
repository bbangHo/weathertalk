// 홈 섹션 강조 이동 (07 §7.3: 강수 예보 알림 → 시간별 예보, 현장 제보 강수 알림 → 현장 제보 현황)
// 경로 파라미터 focus는 푸시 이동(hrefForPushData)과 앱 안 이동이 같이 쓴다.
import type { HomeFocus } from '@/navigation/routes';

const FOCUSES: readonly HomeFocus[] = ['hourly', 'field-report'];

/** 섹션 위쪽에 남길 여백(px) — 섹션 제목이 화면 맨 위에 붙지 않게 */
export const FOCUS_TOP_MARGIN = 12;

/** 경로 파라미터 → 강조할 섹션. 모르는 값·배열·빈 값은 null(맨 위). */
export function parseHomeFocus(value: unknown): HomeFocus | null {
  return typeof value === 'string' && (FOCUSES as readonly string[]).includes(value) ? (value as HomeFocus) : null;
}

/**
 * 스크롤할 위치. 그 섹션의 자리를 아직 모르면 null(자리가 잡힌 뒤 다시 계산한다).
 * @param positions 섹션별 스크롤 내용 안의 y 좌표
 */
export function focusScrollY(focus: HomeFocus | null, positions: Partial<Record<HomeFocus, number>>): number | null {
  if (!focus) {
    return null;
  }
  const y = positions[focus];
  return y === undefined ? null : Math.max(0, y - FOCUS_TOP_MARGIN);
}
