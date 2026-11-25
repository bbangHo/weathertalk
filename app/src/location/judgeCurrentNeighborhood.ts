// 현재 동네 판정 (07 §2 규칙 5·6, 02 FR-NBR-01·02). 좌표 → 동네 ID만 남기고 좌표는 버린다.
// 판정은 기기 안에서 한다(localNeighborhood) — 좌표도 판정 결과도 서버로 보내지 않는다(ADR 0009).
// 회원의 서버 현재 동네는 사용자가 확인한 동작(쓰기, 동네 전환의 '현재 위치' 확인)에서만 바뀐다.
import type { NeighborhoodSummary } from '@/neighborhood/types';
import type { ViewingStore } from '@/neighborhood/viewingStore';

import { getLocationForJudgement, getLocationPermission } from './deviceLocation';
import { type DeviceCoords, locateLocally } from './localNeighborhood';

export type JudgeResult =
  | { status: 'resolved'; neighborhood: NeighborhoodSummary; viewingNeighborhoodId: string }
  | { status: 'no-permission' }
  | { status: 'failed'; error: unknown };

export type JudgeDeps = {
  store: ViewingStore;
  now?: () => Date;
  /** 쓰기 직전처럼 이미 받은 좌표가 있으면 다시 받지 않고 쓴다 */
  location?: DeviceCoords;
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

async function run({ store, now = () => new Date(), location }: JudgeDeps): Promise<JudgeResult> {
  const { permission } = await getLocationPermission();
  if (permission !== 'granted') {
    return { status: 'no-permission' };
  }
  try {
    const neighborhood = await resolve(location);
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

async function resolve(given: DeviceCoords | undefined): Promise<NeighborhoodSummary | null> {
  // 좌표는 이 함수 안의 지역 값으로만 존재한다.
  const location = given ?? (await getLocationForJudgement());
  return locateLocally(location.latitude, location.longitude);
}
