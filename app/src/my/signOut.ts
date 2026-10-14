// 로그아웃·탈퇴 순서 (07 SCR-09 로그아웃·탈퇴, §6.1·6.2 로그아웃·탈퇴 후, §7.1-3 기기-회원 연결 해제, ADR 0008·0010)
// 로그아웃: 서버 토큰 폐기(실패해도 진행) → 기기 회원 데이터 삭제 → 비회원 전환 → 보는 동네·홈 캐시 다시 읽기 → 기기 확인 → 비회원 홈
// 탈퇴: 서버 탈퇴(실패하면 멈추고 사유를 보여준다) → 위와 같은 기기 정리
import { router } from 'expo-router';

import { api } from '@/api';
import { syncDevice } from '@/device/deviceSync';
import { homeCache } from '@/home/homeCache';
import { viewingStore } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { sessionStore } from '@/session/sessionStore';
import { clearMemberDataOnSignOut } from '@/storage';

import { logout, withdraw } from './api';

export type SignOutDeps = {
  serverLogout: () => Promise<unknown>;
  serverWithdraw: () => Promise<unknown>;
  clearMemberData: () => Promise<void>;
  becomeGuest: () => void;
  rehydrateViewing: () => Promise<void>;
  clearHomeMemory: () => void;
  syncDevice: () => Promise<unknown>;
  goHome: () => void;
};

/** 서버 결과와 무관한 기기 정리. 앞 단계가 실패해도 비회원 전환까지는 반드시 간다. */
async function cleanUpDevice(deps: SignOutDeps): Promise<void> {
  try {
    await deps.clearMemberData();
  } catch {
    // 저장소 오류가 있어도 메모리 상태는 비회원으로 돌린다.
  }
  deps.becomeGuest();
  deps.clearHomeMemory();
  await deps.rehydrateViewing().catch(() => undefined);
  // 비회원 요청자로 기기 확인 → 서버가 기기-회원 연결을 끊는다. 실패하면 다음 기기 확인 때 맞춰진다.
  await deps.syncDevice().catch(() => undefined);
  deps.goHome();
}

export async function performLogout(deps: SignOutDeps = defaultSignOutDeps()): Promise<void> {
  // 서버 호출이 실패해도 기기 정리는 진행한다(SCR-09).
  await deps.serverLogout().catch(() => undefined);
  await cleanUpDevice(deps);
}

/** @throws 서버가 탈퇴를 거부·실패하면 그대로 던진다(기기 정리는 하지 않는다). */
export async function performWithdraw(deps: SignOutDeps = defaultSignOutDeps()): Promise<void> {
  await deps.serverWithdraw();
  await cleanUpDevice(deps);
}

export function defaultSignOutDeps(): SignOutDeps {
  return {
    serverLogout: () => logout(api, sessionStore.getTokens()?.refreshToken ?? null),
    serverWithdraw: () => withdraw(api),
    clearMemberData: clearMemberDataOnSignOut,
    becomeGuest: () => sessionStore.becomeGuest(),
    rehydrateViewing: () => viewingStore.hydrate(),
    clearHomeMemory: () => homeCache.clearMemory(),
    syncDevice: () => syncDevice(api),
    goHome: () => {
      try {
        router.replace(routes.home());
      } catch {
        // 내비게이션이 준비되지 않았으면 비회원 상태만으로 충분하다.
      }
    },
  };
}

export const LOGOUT_CONFIRM = '날씨 알림은 이 기기에서 계속 받아요. 댓글 알림은 꺼져요';
export const WITHDRAW_EXPLAIN =
  '탈퇴하면 내가 쓴 글·댓글·좋아요는 지워지고, 체감 기록은 누가 남겼는지 알 수 없는 기록으로 남아요. 탈퇴 후 바로 다시 가입할 수 있어요.';
export const WITHDRAW_DONE = '날씨 알림은 계속 받을 수 있어요. 알림 구독에서 끌 수 있어요';
