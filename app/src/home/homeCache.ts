// 마지막 홈 응답 (07 §2-9 스플래시, §6.1). 오프라인·느린 시작 때 관측 시각과 함께 먼저 보여준다.
// 보는 동네가 바뀌면 다음 성공 응답으로 교체하고, 다른 동네의 응답은 보여주지 않는다.
import { generalStore } from '@/storage';

import type { HomeView } from './types';

export type CachedHome = { neighborhoodId: string; savedAt: string; body: HomeView };

let memory: CachedHome | null = null;

function isHomeView(value: unknown): value is HomeView {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  const record = value as Record<string, unknown>;
  return typeof record.neighborhood === 'object' && record.neighborhood !== null && 'currentWeather' in record;
}

export const homeCache = {
  async load(): Promise<CachedHome | null> {
    const stored = await generalStore.get('lastHomeResponse');
    memory = stored && isHomeView(stored.body) ? { ...stored, body: stored.body } : null;
    return memory;
  },

  /** 보는 동네의 마지막 응답. 다른 동네 것이면 null */
  peek(neighborhoodId: string | null): CachedHome | null {
    if (!memory) {
      return null;
    }
    // 회원이 동네를 지정하지 않은 경우(null)엔 응답의 동네가 곧 보는 동네다.
    return neighborhoodId === null || memory.neighborhoodId === neighborhoodId ? memory : null;
  },

  async save(body: HomeView, now: Date = new Date()): Promise<void> {
    memory = { neighborhoodId: body.neighborhood.id, savedAt: now.toISOString(), body };
    try {
      await generalStore.set('lastHomeResponse', memory);
    } catch {
      // 캐시 저장 실패는 화면에 영향을 주지 않는다.
    }
  },

  clearMemory(): void {
    memory = null;
  },
};
