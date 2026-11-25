// 앱 시작·포그라운드 복귀 처리 (07 §2 규칙 6·9·11, §6.1)
// - 스플래시: 세션 복원 + 기기 저장값 읽기가 끝나면 내린다. 홈은 마지막 홈 응답을 먼저 보여준다.
// - 위치 권한이 있으면 app.location-refresh-interval 경과 시 현재 동네 재판정, 권한이 없어졌으면 현재 동네 삭제
// - 기기 확인(실패해도 화면을 막지 않음)
// - 가입 중 종료됐던 보류 행동: 회원이면 복원, 아니면 지움(07 §3 규칙 6)
import * as SplashScreen from 'expo-splash-screen';
import { useEffect } from 'react';
import { AppState } from 'react-native';

import { api } from '@/api';
import { getAppParams } from '@/config/appParams';
import { syncDevice } from '@/device/deviceSync';
import { homeCache } from '@/home/homeCache';
import { getLocationPermission } from '@/location/deviceLocation';
import { judgeCurrentNeighborhood } from '@/location/judgeCurrentNeighborhood';
import { viewingStore } from '@/neighborhood/viewingStore';
import { resumePendingOnLaunch } from '@/pending/restore';
import { sessionStore } from '@/session/sessionStore';

import { decideEntry, shouldRejudge } from './entryDecision';

function waitForSessionRestored(): Promise<void> {
  if (sessionStore.getState().status !== 'restoring') {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const unsubscribe = sessionStore.subscribe(() => {
      if (sessionStore.getState().status !== 'restoring') {
        unsubscribe();
        resolve();
      }
    });
  });
}

function isMember(): boolean {
  return sessionStore.getState().status === 'member';
}

/** 시작 때 한 번: 저장값을 읽고 진입 판단을 적용한다. */
export async function startApp(now: number = Date.now()): Promise<void> {
  await Promise.all([
    waitForSessionRestored(),
    viewingStore.hydrate().catch(() => undefined),
    homeCache.load().catch(() => null),
  ]);
  const { permission } = await getLocationPermission();
  const state = viewingStore.getState();
  const decision = decideEntry({
    onboardingCompleted: state.onboardingCompleted,
    locationPermission: permission,
    current: state.current,
    lastViewedNeighborhoodId: state.viewingNeighborhoodId,
    now,
    locationRefreshIntervalMs: getAppParams().locationRefreshIntervalMs,
  });
  if (decision.screen === 'welcome') {
    return;
  }
  if (decision.clearCurrent) {
    await viewingStore.clearCurrent().catch(() => undefined);
  }
  if (decision.viewingNeighborhoodId !== state.viewingNeighborhoodId) {
    await viewingStore.setViewing(decision.viewingNeighborhoodId).catch(() => undefined);
  }
  if (decision.rejudge) {
    // 판정은 기다리지 않는다 — 그동안 마지막으로 보던 동네를 보여준다.
    void judgeCurrentNeighborhood({ store: viewingStore });
  }
}

/** 포그라운드 복귀: 권한 상태 재확인 + 간격이 지났으면 재판정 */
export async function onForeground(now: number = Date.now()): Promise<void> {
  const state = viewingStore.getState();
  if (!state.hydrated || !state.onboardingCompleted) {
    return;
  }
  const { permission } = await getLocationPermission();
  if (permission !== 'granted') {
    await viewingStore.clearCurrent().catch(() => undefined);
    return;
  }
  if (shouldRejudge(state.current, now, getAppParams().locationRefreshIntervalMs)) {
    await judgeCurrentNeighborhood({ store: viewingStore });
  }
}

export function AppLifecycle() {
  useEffect(() => {
    let active = true;
    startApp()
      .catch(() => undefined)
      .finally(() => {
        if (active) {
          SplashScreen.hide();
        }
        void syncDevice(api);
        // 가입 중 앱이 종료됐던 경우(07 §3 규칙 6)
        resumePendingOnLaunch(isMember()).catch(() => undefined);
      });

    let previous = AppState.currentState;
    const subscription = AppState.addEventListener('change', (next) => {
      const cameToForeground = next === 'active' && previous !== 'active';
      previous = next;
      if (cameToForeground) {
        void syncDevice(api);
        onForeground().catch(() => undefined);
      }
    });
    return () => {
      active = false;
      subscription.remove();
    };
  }, []);

  return null;
}
