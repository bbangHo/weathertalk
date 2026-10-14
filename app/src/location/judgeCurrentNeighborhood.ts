// 현재 동네 판정 (07 §2 규칙 5·6, 02 FR-NBR-01·02). 좌표 → 동네 ID만 남기고 좌표는 버린다.
// 비회원은 저장 없는 판정(locate), 회원은 현재 동네 갱신(PUT members/me/current-neighborhood)으로 서버의 현재 동네도 맞춘다.
import type { ApiClient } from '@/api/client';
import { locateNeighborhood, refreshMemberCurrentNeighborhood } from '@/neighborhood/api';
import type { LocationRequest, NeighborhoodSummary } from '@/neighborhood/types';
import type { ViewingStore } from '@/neighborhood/viewingStore';

import { getLocationForJudgement, getLocationPermission } from './deviceLocation';

export type JudgeResult =
  | { status: 'resolved'; neighborhood: NeighborhoodSummary; viewingNeighborhoodId: string }
  | { status: 'no-permission' }
  | { status: 'failed'; error: unknown };

export type JudgeDeps = {
  api: ApiClient;
  store: ViewingStore;
  isMember: boolean;
  now?: () => Date;
  /** 쓰기 직전처럼 이미 받은 좌표가 있으면 다시 받지 않고 쓴다 */
  location?: LocationRequest;
};

let inFlight: Promise<JudgeResult> | null = null;

/** 동시에 여러 곳(시작·포그라운드 복귀·화면)에서 불러도 판정은 한 번만 진행한다. */
export function judgeCurrentNeighborhood(deps: JudgeDeps): Promise<JudgeResult> {
  if (!inFlight) {
    inFlight = run(deps).finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

async function run({ api, store, isMember, now = () => new Date(), location }: JudgeDeps): Promise<JudgeResult> {
  const { permission } = await getLocationPermission();
  if (permission !== 'granted') {
    return { status: 'no-permission' };
  }
  try {
    const neighborhood = await resolve(api, isMember, location);
    if (!neighborhood) {
      return { status: 'failed', error: new Error('현재 동네를 찾지 못했어요.') };
    }
    const viewingNeighborhoodId = await store.applyJudgement(neighborhood.id, now().toISOString());
    return { status: 'resolved', neighborhood, viewingNeighborhoodId };
  } catch (error) {
    // 판정 실패는 저장된 현재 동네를 바꾸지 않는다(FR-NBR-02 실패). 오류 내용에 좌표가 없으므로 그대로 돌려준다.
    return { status: 'failed', error };
  }
}

async function resolve(
  api: ApiClient,
  isMember: boolean,
  given: LocationRequest | undefined,
): Promise<NeighborhoodSummary | null> {
  // 좌표는 이 함수 안의 지역 값으로만 존재한다.
  const location = given ?? (await getLocationForJudgement());
  return isMember ? refreshMemberCurrentNeighborhood(api, location) : locateNeighborhood(api, location);
}
