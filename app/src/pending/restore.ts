// 보류 행동 복원 연결 (07 §3 흐름 MV 이후, 규칙 6). 순수 복원 규칙은 pendingAction.ts에 있다.
import { router, type Href } from 'expo-router';

import { ensureFreshCurrentNeighborhood } from '@/location/writeLocation';
import { viewingStore } from '@/neighborhood/viewingStore';

import {
  compareWithCurrent,
  type PendingAction,
  pendingActionStore,
  type RestoreDeps,
  restorePendingAction,
  type RestoreOutcome,
} from './pendingAction';

async function checkCurrentNeighborhood(action: PendingAction) {
  const currentId = await ensureFreshCurrentNeighborhood({ store: viewingStore });
  return compareWithCurrent(action, currentId);
}

function baseDeps(navigate: RestoreDeps['navigate']): RestoreDeps {
  return {
    load: () => pendingActionStore.load(),
    clear: () => pendingActionStore.clear(),
    checkCurrentNeighborhood,
    navigate,
  };
}

/**
 * 가입 시트에서 로그인·가입·재동의를 마친 뒤. 원래 화면은 시트 아래에 그대로 있으므로 시트를 닫아 돌아간다.
 * 보류 행동이 없어도(오류 신호로 열린 시트) 시트는 닫는다.
 */
export async function restoreAfterSignup(closeSheet: () => void): Promise<RestoreOutcome> {
  try {
    return await restorePendingAction(baseDeps(() => undefined));
  } finally {
    closeSheet();
  }
}

/**
 * 앱 재시작(§3 규칙 6): 가입 중 앱이 종료됐다면 회원이면 원래 화면으로 이동해 복원하고, 아니면 보류 행동만 지운다(초안은 유지).
 */
export async function resumePendingOnLaunch(isMember: boolean): Promise<RestoreOutcome> {
  if (!isMember) {
    await pendingActionStore.clear().catch(() => undefined);
    return { status: 'none' };
  }
  return restorePendingAction(
    baseDeps((action) => {
      if (action.route === '/') {
        return;
      }
      try {
        router.push(action.route as Href);
      } catch {
        // 내비게이션이 아직 준비되지 않았으면 복원 내용만 남는다(그 화면을 열면 받는다).
      }
    }),
  );
}
