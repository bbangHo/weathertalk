// 쓰기 직전 현재 동네 (07 §1.6 app.write-location-max-age, §3 흐름 NL→LC→LP, SCR-04 진입 조건, ADR 0004·0009)
// 마지막 판정이 max-age보다 오래됐으면 다시 판정한다. 서버에는 좌표 대신 기기가 판정한 동네 ID만 보낸다.
import { getAppParams } from '@/config/appParams';
import type { ViewingStore } from '@/neighborhood/viewingStore';
import type { StoredCurrentNeighborhood } from '@/storage';

import { getLocationForJudgement, getLocationPermission } from './deviceLocation';
import { judgeCurrentNeighborhood } from './judgeCurrentNeighborhood';
import type { DeviceCoords } from './localNeighborhood';

export type WriteLocationDeps = {
  store: ViewingStore;
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
  const result = await judgeCurrentNeighborhood({ store: deps.store });
  return result.status === 'resolved' ? result.neighborhood.id : null;
}

export type WriteLocation = { status: 'ok'; neighborhoodId: string } | { status: 'unavailable' };

/** 쓰기에 쓸 만큼 정확한 위치인가(neighborhood.max-accuracy-for-write, 경계 포함). 정확도를 모르면 쓰지 않는다. */
function isAccurateForWrite(location: DeviceCoords): boolean {
  const accuracy = location.accuracyMeters;
  return accuracy !== null && accuracy <= getAppParams().maxAccuracyForWriteMeters;
}

/**
 * 쓰기 요청 직전: 좌표를 새로 받아 정확도를 확인하고(FR-NBR-03), 판정이 오래됐으면 같은 좌표로 기기 안에서 다시 판정한다.
 * 정확도 미달·판정 불가면 unavailable — 화면은 지금의 쓰기 실패 안내를 보여준다.
 */
export async function prepareWriteLocation(deps: WriteLocationDeps): Promise<WriteLocation> {
  const now = deps.now ?? Date.now;
  const { permission } = await getLocationPermission();
  if (permission !== 'granted') {
    return { status: 'unavailable' };
  }
  let location: DeviceCoords;
  try {
    location = await getLocationForJudgement();
  } catch {
    return { status: 'unavailable' };
  }
  if (!isAccurateForWrite(location)) {
    return { status: 'unavailable' };
  }
  const current = deps.store.getState().current;
  if (isJudgementFresh(current, now(), getAppParams().writeLocationMaxAgeMs)) {
    return { status: 'ok', neighborhoodId: current!.neighborhoodId };
  }
  const result = await judgeCurrentNeighborhood({ store: deps.store, location });
  return result.status === 'resolved' ? { status: 'ok', neighborhoodId: result.neighborhood.id } : { status: 'unavailable' };
}
