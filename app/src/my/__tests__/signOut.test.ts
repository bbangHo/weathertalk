// 로그아웃·탈퇴 순서 (07 SCR-09): 서버 폐기 → 기기 회원 데이터 삭제 → 비회원 전환 → 보는 동네·홈 캐시 → 기기 확인 → 비회원 홈.
// 로그아웃은 서버가 실패해도 진행하고, 탈퇴는 서버가 거부하면 기기를 정리하지 않는다.
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';

import { viewingStore } from '@/neighborhood/viewingStore';
import { sessionStore } from '@/session/sessionStore';
import { generalStore, secureStore } from '@/storage';
import { jsonResponse, problem, tokens } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';

import { defaultSignOutDeps, performLogout, performWithdraw, type SignOutDeps } from '../signOut';

// 팩토리 안에서는 import를 쓸 수 없다(호이스팅).
// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

function recordingDeps(overrides: Partial<SignOutDeps> = {}) {
  const order: string[] = [];
  const step =
    (name: string) =>
    async (): Promise<void> => {
      order.push(name);
    };
  const deps: SignOutDeps = {
    serverLogout: step('serverLogout'),
    serverWithdraw: step('serverWithdraw'),
    clearMemberData: step('clearMemberData'),
    becomeGuest: () => {
      order.push('becomeGuest');
    },
    rehydrateViewing: step('rehydrateViewing'),
    clearHomeMemory: () => {
      order.push('clearHomeMemory');
    },
    syncDevice: step('syncDevice'),
    goHome: () => {
      order.push('goHome');
    },
    ...overrides,
  };
  return { deps, order };
}

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  viewingStore.reset();
  resetRouterMock();
});

it('로그아웃 순서: 서버 폐기 → 기기 정리 → 비회원 → 홈 캐시·보는 동네 → 기기 확인 → 홈', async () => {
  const { deps, order } = recordingDeps();
  await performLogout(deps);
  expect(order).toEqual([
    'serverLogout',
    'clearMemberData',
    'becomeGuest',
    'clearHomeMemory',
    'rehydrateViewing',
    'syncDevice',
    'goHome',
  ]);
});

it('로그아웃 서버 호출이 실패해도 기기 정리는 끝까지 진행한다', async () => {
  const { deps, order } = recordingDeps({
    serverLogout: async () => {
      order.push('serverLogout');
      throw new Error('offline');
    },
  });
  await expect(performLogout(deps)).resolves.toBeUndefined();
  expect(order.slice(1)).toEqual(['clearMemberData', 'becomeGuest', 'clearHomeMemory', 'rehydrateViewing', 'syncDevice', 'goHome']);
});

it('탈퇴는 서버 탈퇴 뒤 같은 기기 정리를 하고, 서버가 거부하면 아무것도 지우지 않고 사유를 던진다', async () => {
  const ok = recordingDeps();
  await performWithdraw(ok.deps);
  expect(ok.order).toEqual([
    'serverWithdraw',
    'clearMemberData',
    'becomeGuest',
    'clearHomeMemory',
    'rehydrateViewing',
    'syncDevice',
    'goHome',
  ]);

  const failing = recordingDeps({
    serverWithdraw: async () => {
      throw new Error('거부');
    },
  });
  await expect(performWithdraw(failing.deps)).rejects.toThrow('거부');
  expect(failing.order).toEqual([]);
});

it('실제 의존으로: 갱신 토큰으로 폐기 요청 후 회원 데이터만 지우고 비회원 요청자로 기기 확인, 홈으로 간다', async () => {
  await sessionStore.saveTokens(tokens('m'));
  await secureStore.set('deviceCredential', { deviceId: 'd1', deviceKey: 'k1' });
  await generalStore.set('onboardingCompleted', true);
  await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'N1' });
  await generalStore.set('currentNeighborhood', { neighborhoodId: 'N1', resolvedAt: '2026-10-15T00:00:00Z' });
  await generalStore.set('postDraft', { body: '초안', images: [], feelingRecordId: null, savedAt: 'x' });
  await generalStore.set('guestInterestNeighborhoods', ['N3']);
  await viewingStore.hydrate();
  const { calls } = installFetch((call) =>
    pathOf(call) === '/api/v1/auth/logout' ? problem(503, 'TEMPORARILY_UNAVAILABLE') : jsonResponse(200, {}),
  );

  await performLogout(defaultSignOutDeps());

  expect(calls.map(pathOf)).toEqual(['/api/v1/auth/logout', '/api/v1/notification/devices/confirm']);
  expect(calls[0].body).toEqual({ refreshToken: 'refresh-m' });
  expect(calls[0].headers.Authorization).toBeUndefined();
  // 기기 확인은 비회원 요청자로(토큰 없이) 보낸다
  expect(calls[1].headers.Authorization).toBeUndefined();
  expect(sessionStore.getState().status).toBe('guest');
  expect(await secureStore.get('authTokens')).toBeNull();
  expect(await secureStore.get('deviceCredential')).toEqual({ deviceId: 'd1', deviceKey: 'k1' });
  expect(await generalStore.get('postDraft')).toBeNull();
  expect(await generalStore.get('lastViewedNeighborhood')).toBeNull();
  expect(await generalStore.get('guestInterestNeighborhoods')).toEqual(['N3']);
  expect(viewingStore.getState()).toMatchObject({ viewingNeighborhoodId: null, current: null, onboardingCompleted: true });
  expect(mockRouter.replace).toHaveBeenCalledWith('/');
});
