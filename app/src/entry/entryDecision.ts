// 앱 진입 판단 (07 §2 흐름, 규칙 2·3·6, §6.1 현재 동네·마지막으로 보던 동네). 부수효과 없는 순수 함수만 둔다.
import type { StoredCurrentNeighborhood } from '@/storage';

export type LocationPermission = 'granted' | 'denied' | 'undetermined';

export type EntryInput = {
  onboardingCompleted: boolean;
  locationPermission: LocationPermission;
  current: StoredCurrentNeighborhood | null;
  lastViewedNeighborhoodId: string | null;
  now: number;
  locationRefreshIntervalMs: number;
};

export type EntryDecision =
  | { screen: 'welcome' }
  | {
      screen: 'home';
      /** 처음 보여줄 보는 동네. null이면 "동네를 골라 주세요" */
      viewingNeighborhoodId: string | null;
      /** 지금 현재 동네를 다시 판정할지 */
      rejudge: boolean;
      /** 위치 권한이 없어졌으니 기기의 현재 동네를 지운다(§6.1 지우는 때) */
      clearCurrent: boolean;
    };

/** 마지막 판정 후 app.location-refresh-interval이 지났거나 판정한 적이 없으면 다시 판정한다(규칙 6). */
export function shouldRejudge(current: StoredCurrentNeighborhood | null, now: number, intervalMs: number): boolean {
  if (!current) {
    return true;
  }
  const resolvedAt = Date.parse(current.resolvedAt);
  // 시각이 깨졌거나 기기 시계가 뒤로 갔으면 다시 판정하는 쪽이 안전하다.
  if (!Number.isFinite(resolvedAt) || resolvedAt > now) {
    return true;
  }
  return now - resolvedAt >= intervalMs;
}

export function decideEntry(input: EntryInput): EntryDecision {
  if (!input.onboardingCompleted) {
    return { screen: 'welcome' };
  }
  if (input.locationPermission === 'granted') {
    return {
      screen: 'home',
      viewingNeighborhoodId: input.lastViewedNeighborhoodId ?? input.current?.neighborhoodId ?? null,
      rejudge: shouldRejudge(input.current, input.now, input.locationRefreshIntervalMs),
      clearCurrent: false,
    };
  }
  // 권한이 없으면 마지막으로 보던 동네(규칙 흐름 Q→H3). 재판정하지 않고 권한도 다시 묻지 않는다(규칙 3).
  return {
    screen: 'home',
    viewingNeighborhoodId: input.lastViewedNeighborhoodId,
    rejudge: false,
    clearCurrent: input.current !== null,
  };
}

/**
 * 재판정 결과를 보는 동네에 반영한다(규칙 6): 보는 동네가 이전 현재 동네였을 때만 따라 바꾼다.
 * 보는 동네가 아예 없으면 판정된 현재 동네를 보는 동네로 정한다.
 */
export function followJudgement(params: {
  viewingNeighborhoodId: string | null;
  previousCurrentId: string | null;
  nextCurrentId: string;
}): string {
  const { viewingNeighborhoodId, previousCurrentId, nextCurrentId } = params;
  if (viewingNeighborhoodId === null) {
    return nextCurrentId;
  }
  if (previousCurrentId !== null && viewingNeighborhoodId === previousCurrentId) {
    return nextCurrentId;
  }
  return viewingNeighborhoodId;
}

/** 권한 사전 안내를 다시 보여줄 수 있는가(규칙 3, §1.6 app.permission-preprompt-cooldown). */
export function canShowPreprompt(declinedAt: string | undefined, now: number, cooldownMs: number): boolean {
  if (!declinedAt) {
    return true;
  }
  const at = Date.parse(declinedAt);
  if (!Number.isFinite(at)) {
    return true;
  }
  return now - at >= cooldownMs;
}
