// 현재 동네 판정 (07 §2 규칙 5·6, 02 FR-NBR-01·02). 좌표 → 동네만 남기고 좌표는 버린다.
// 판정은 기기 안에서 한다(localNeighborhood). 판정 결과는 사용자가 확인하기 전에는 보는 동네·현재 동네를 바꾸지 않고
// 서버로도 보내지 않는다(ADR 0009) — 자동 판정은 제안(suggestion)으로만 둔다.
import type { ApiClient } from '@/api/client';
import { setMemberCurrentNeighborhood } from '@/neighborhood/api';
import type { NeighborhoodSummary } from '@/neighborhood/types';
import type { ViewingStore } from '@/neighborhood/viewingStore';

import { getLocationForJudgement, getLocationPermission } from './deviceLocation';
import { type DeviceCoords, locateLocally } from './localNeighborhood';

export type JudgeResult =
  | { status: 'resolved'; neighborhood: NeighborhoodSummary }
  | { status: 'no-permission' }
  | { status: 'failed'; error: unknown };

export type JudgeDeps = {
  /** 쓰기 직전처럼 이미 받은 좌표가 있으면 다시 받지 않고 쓴다 */
  location?: DeviceCoords;
};

let inFlight: Promise<JudgeResult> | null = null;

/** 좌표 → 동네. 아무것도 저장하지 않는다. 동시에 여러 곳에서 불러도 좌표 받기는 한 번만 한다. */
export function judgeCurrentNeighborhood(deps: JudgeDeps = {}): Promise<JudgeResult> {
  if (deps.location) {
    return run(deps.location);
  }
  if (!inFlight) {
    inFlight = run().finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

async function run(given?: DeviceCoords): Promise<JudgeResult> {
  const { permission } = await getLocationPermission();
  if (permission !== 'granted') {
    return { status: 'no-permission' };
  }
  try {
    // 좌표는 이 함수 안의 지역 값으로만 존재한다.
    const location = given ?? (await getLocationForJudgement());
    const neighborhood = locateLocally(location.latitude, location.longitude);
    return neighborhood
      ? { status: 'resolved', neighborhood }
      : { status: 'failed', error: new Error('현재 동네를 찾지 못했어요.') };
  } catch (error) {
    // 오류 내용에 좌표가 없으므로 그대로 돌려준다.
    return { status: 'failed', error };
  }
}

/**
 * 자동 판정(앱 진입·포그라운드 복귀·로그인 직후). 보는 동네는 바꾸지 않는다.
 * 판정한 동네가 보는 동네와 같으면 판정 시각만 남기고, 다르면 제안으로 둔다. 판정 실패는 아무것도 바꾸지 않는다.
 */
export async function judgeAndSuggest(store: ViewingStore, now: () => Date = () => new Date()): Promise<void> {
  const result = await judgeCurrentNeighborhood();
  if (result.status !== 'resolved') {
    return;
  }
  if (result.neighborhood.id === store.getState().viewingNeighborhoodId) {
    store.dismissSuggestion();
    await store.applyJudgement(result.neighborhood.id, now().toISOString());
  } else {
    store.suggest(result.neighborhood);
  }
}

/**
 * 사용자가 확인한 판정 결과를 적용한다: 현재 동네·보는 동네를 그 동네로 바꾸고, 회원이면 서버의 현재 동네도 동네 ID로 맞춘다.
 * 기기 반영은 서버 실패와 무관하게 끝난다. 서버 반영 실패는 던진다(호출한 화면이 알린다).
 */
export async function adoptNeighborhood(
  store: ViewingStore,
  neighborhoodId: string,
  member: { api: ApiClient } | null,
  now: () => Date = () => new Date(),
): Promise<void> {
  store.dismissSuggestion();
  await store.applyJudgement(neighborhoodId, now().toISOString());
  await store.setViewing(neighborhoodId);
  if (member) {
    await setMemberCurrentNeighborhood(member.api, neighborhoodId);
  }
}
