// 데모 모드: 웹에서 /preview로 들어오면 켜진다. 앱 전체가 가짜 서버(demoServer)와 메모리 저장소를 써서
// 진짜 서버·기기 저장소를 건드리지 않는다. 새로고침해도 이어지도록 탭 단위(sessionStorage)로 기억한다.
// 앱인토스 빌드는 demoMode.ait.ts가 대신해 데모 코드·데이터가 번들에 들어가지 않는다.
import { Platform } from 'react-native';

import { STORAGE_KEY_PREFIX } from '@/storage/schema';

import { createDemoServer, DEMO_CURRENT_ID } from './demoServer';

const FLAG = 'wt.demo';
export const DEMO_ENTRY_PATH = '/preview';

function detect(): boolean {
  if (Platform.OS !== 'web' || typeof window === 'undefined') {
    return false;
  }
  const entering = window.location.pathname.startsWith(DEMO_ENTRY_PATH);
  try {
    if (entering) {
      window.sessionStorage.setItem(FLAG, '1');
    }
    return entering || window.sessionStorage.getItem(FLAG) === '1';
  } catch {
    return entering;
  }
}

export const DEMO_MODE = detect();

/** API 클라이언트에 끼울 가짜 서버 fetch. 데모가 아니면 undefined(기본 fetch) */
export const demoFetch: typeof fetch | undefined = DEMO_MODE ? createDemoServer().fetch : undefined;

/** 데모 회원이 이미 첫 실행을 마치고 역삼1동에 있는 상태로 시작한다. */
function seededStorage() {
  const now = new Date().toISOString();
  const seed: Record<string, unknown> = {
    authTokens: {
      accessToken: 'demo-access',
      accessTokenExpiresAt: now,
      refreshToken: 'demo-refresh',
      refreshTokenExpiresAt: now,
    },
    onboardingCompleted: true,
    currentNeighborhood: { neighborhoodId: DEMO_CURRENT_ID, resolvedAt: now },
    lastViewedNeighborhood: { neighborhoodId: DEMO_CURRENT_ID },
    guestTemperatureSensitivity: 'NORMAL',
  };
  const map = new Map(Object.entries(seed).map(([key, value]) => [STORAGE_KEY_PREFIX + key, JSON.stringify(value)]));
  return {
    getItem: async (key: string) => map.get(key) ?? null,
    setItem: async (key: string, value: string) => void map.set(key, value),
    multiRemove: async (keys: readonly string[]) => keys.forEach((key) => map.delete(key)),
    getItemAsync: async (key: string) => map.get(key) ?? null,
    setItemAsync: async (key: string, value: string) => void map.set(key, value),
    deleteItemAsync: async (key: string) => void map.delete(key),
  };
}

/** 기기 저장소 대신 쓰는 메모리 저장소(데모가 아니면 null). 탭을 닫으면 사라진다. */
export const demoStorage = DEMO_MODE ? seededStorage() : null;

/** 데모를 끝내고 실제 앱 첫 화면으로 다시 연다(메모리 상태를 버리려고 새로 불러온다). */
export function exitDemo(): void {
  try {
    window.sessionStorage.removeItem(FLAG);
  } catch {
    // 저장소를 못 써도 새로 불러오면 /preview가 아니므로 데모가 아니다.
  }
  window.location.assign('/');
}
