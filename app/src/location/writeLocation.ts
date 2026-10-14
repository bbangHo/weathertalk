// 쓰기 직전 현재 동네 (07 §1.6 app.write-location-max-age, §3 흐름 NL→LC→LP, SCR-04 진입 조건, ADR 0004·0009)
// 마지막 판정이 max-age보다 오래됐으면 다시 판정한다. 쓰기 요청 본문용 좌표는 호출한 함수 안에서만 쓰고 저장하지 않는다.
import type { ApiClient } from '@/api/client';
import { getAppParams } from '@/config/appParams';
import type { LocationRequest } from '@/neighborhood/types';
import type { ViewingStore } from '@/neighborhood/viewingStore';
import type { StoredCurrentNeighborhood } from '@/storage';

import { getLocationForJudgement, getLocationPermission } from './deviceLocation';
import { judgeCurrentNeighborhood } from './judgeCurrentNeighborhood';

export type WriteLocationDeps = {
  api: ApiClient;
  store: ViewingStore;
  isMember: boolean;
  now?: () => number;
};

/** 판정 시각이 max-age 안인가. 시각이 깨졌거나 미래면 오래된 것으로 본다. */
export function isJudgementFresh(current: StoredCurrentNeighborhood | null, now: number, maxAgeMs: number): boolean {
  if (!current) {
    return false;
  }
  const at = Date.parse(current.resolvedAt);
  return Number.isFinite(at) && at <= now && now - at < maxAgeMs;
}

/**
 * 좌표 없이 현재 동네만 필요할 때(보류 행동 복원). 판정이 신선하면 그대로, 아니면 다시 판정한다.
 * @returns 현재 동네 ID, 판정할 수 없으면 null
 */
export async function ensureFreshCurrentNeighborhood(deps: WriteLocationDeps): Promise<string | null> {
  const now = deps.now ?? Date.now;
  const current = deps.store.getState().current;
  if (isJudgementFresh(current, now(), getAppParams().writeLocationMaxAgeMs)) {
    return current!.neighborhoodId;
  }
  const result = await judgeCurrentNeighborhood({ api: deps.api, store: deps.store, isMember: deps.isMember });
  return result.status === 'resolved' ? result.neighborhood.id : null;
}

export type WriteLocation =
  | { status: 'ok'; neighborhoodId: string; location: LocationRequest }
  | { status: 'unavailable' };

/**
 * 쓰기 요청 직전: 서버가 좌표로 현재 동네를 확정하므로(FR-NBR-03) 좌표는 매번 새로 받는다.
 * 앱의 판정이 오래됐으면 같은 좌표로 다시 판정해 화면의 현재 동네도 맞춘다.
 */
export async function prepareWriteLocation(deps: WriteLocationDeps): Promise<WriteLocation> {
  const now = deps.now ?? Date.now;
  const { permission } = await getLocationPermission();
  if (permission !== 'granted') {
    return { status: 'unavailable' };
  }
  let location: LocationRequest;
  try {
    location = await getLocationForJudgement();
  } catch {
    return { status: 'unavailable' };
  }
  const current = deps.store.getState().current;
  if (isJudgementFresh(current, now(), getAppParams().writeLocationMaxAgeMs)) {
    return { status: 'ok', neighborhoodId: current!.neighborhoodId, location };
  }
  const result = await judgeCurrentNeighborhood({ api: deps.api, store: deps.store, isMember: deps.isMember, location });
  return result.status === 'resolved'
    ? { status: 'ok', neighborhoodId: result.neighborhood.id, location }
    : { status: 'unavailable' };
}
