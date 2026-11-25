// 보는 동네·현재 동네 상태 (07 §1.7 보는 동네, §2 규칙 5·6, §4 규칙 2: 홈·커뮤니티 탭 공유, §6.1)
// 보는 동네 종류(현재/관심/검색)는 저장하지 않고 현재 동네·관심 동네 목록과 비교해 정한다.
import { useSyncExternalStore } from 'react';

import { followJudgement } from '@/entry/entryDecision';
import { generalStore, type StoredCurrentNeighborhood } from '@/storage';

import type { NeighborhoodSummary } from './types';

export type ViewingState = {
  /** 저장소에서 읽기를 마쳤는가 */
  hydrated: boolean;
  onboardingCompleted: boolean;
  viewingNeighborhoodId: string | null;
  /** 사용자가 확인한(또는 보는 동네와 같았던) 현재 동네(좌표 아님) */
  current: StoredCurrentNeighborhood | null;
  /**
   * 자동 판정이 찾은, 보는 동네와 다른 동네 — 확인 전이라 서버로 보내지 않는다(ADR 0009).
   * 메모리에만 두고 닫으면 버린다(다음 진입 때 다시 판정).
   */
  suggestion: NeighborhoodSummary | null;
};

export type ViewingKind = 'current' | 'interest' | 'searched';

type Listener = () => void;

const INITIAL: ViewingState = {
  hydrated: false,
  onboardingCompleted: false,
  viewingNeighborhoodId: null,
  current: null,
  suggestion: null,
};

export function createViewingStore() {
  let state: ViewingState = INITIAL;
  const listeners = new Set<Listener>();

  function setState(patch: Partial<ViewingState>) {
    state = { ...state, ...patch };
    listeners.forEach((listener) => listener());
  }

  async function persistViewing(id: string | null) {
    if (id === null) {
      await generalStore.remove('lastViewedNeighborhood');
    } else {
      await generalStore.set('lastViewedNeighborhood', { neighborhoodId: id });
    }
  }

  return {
    getState: (): ViewingState => state,
    subscribe(listener: Listener): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    /** 앱 시작·로그아웃 정리 후 저장소에서 다시 읽는다. */
    async hydrate(): Promise<void> {
      const [onboardingCompleted, lastViewed, current] = await Promise.all([
        generalStore.get('onboardingCompleted'),
        generalStore.get('lastViewedNeighborhood'),
        generalStore.get('currentNeighborhood'),
      ]);
      setState({
        hydrated: true,
        onboardingCompleted: onboardingCompleted === true,
        viewingNeighborhoodId: lastViewed?.neighborhoodId ?? null,
        current: current ?? null,
      });
    },

    /** 첫 실행 완료는 어느 선택이든 기기에 남긴다(SCR-12). */
    async completeOnboarding(): Promise<void> {
      setState({ onboardingCompleted: true });
      await generalStore.set('onboardingCompleted', true);
    },

    async setViewing(neighborhoodId: string | null): Promise<void> {
      // 제안한 동네를 직접 골랐으면 제안은 끝난 것이다.
      const suggestion = state.suggestion?.id === neighborhoodId ? null : state.suggestion;
      setState({ viewingNeighborhoodId: neighborhoodId, suggestion });
      await persistViewing(neighborhoodId);
    },

    /**
     * 현재 동네 판정 결과 반영. 보는 동네가 이전 현재 동네였으면 따라 바꾼다(§2 규칙 6).
     * @returns 반영 후 보는 동네 ID
     */
    async applyJudgement(neighborhoodId: string, resolvedAt: string): Promise<string> {
      const nextViewing = followJudgement({
        viewingNeighborhoodId: state.viewingNeighborhoodId,
        previousCurrentId: state.current?.neighborhoodId ?? null,
        nextCurrentId: neighborhoodId,
      });
      const current = { neighborhoodId, resolvedAt };
      setState({ current, viewingNeighborhoodId: nextViewing });
      await Promise.all([generalStore.set('currentNeighborhood', current), persistViewing(nextViewing)]);
      return nextViewing;
    },

    /** 자동 판정 결과를 제안으로만 둔다. 보는 동네·현재 동네는 바꾸지 않는다. */
    suggest(neighborhood: NeighborhoodSummary): void {
      setState({ suggestion: neighborhood });
    },

    dismissSuggestion(): void {
      if (state.suggestion !== null) {
        setState({ suggestion: null });
      }
    },

    /** 위치 권한이 해제됐을 때(§6.1 지우는 때). 보는 동네는 마지막 값 그대로 둔다. */
    async clearCurrent(): Promise<void> {
      if (state.current === null) {
        this.dismissSuggestion();
        return;
      }
      setState({ current: null, suggestion: null });
      await generalStore.remove('currentNeighborhood');
    },

    /** 테스트·로그아웃 직후 메모리 초기화 */
    reset(): void {
      setState(INITIAL);
    },
  };
}

export type ViewingStore = ReturnType<typeof createViewingStore>;

export const viewingStore = createViewingStore();

export function useViewingState(): ViewingState {
  return useSyncExternalStore(viewingStore.subscribe, viewingStore.getState, viewingStore.getState);
}

/** 보는 동네 종류: 현재 동네 → 관심 동네 → 검색으로 고른 동네(보기 전용) 순으로 본다. */
export function viewingKindOf(params: {
  viewingNeighborhoodId: string;
  currentNeighborhoodId: string | null;
  /** 회원이면 home 응답의 현재 동네 여부 */
  serverSaysCurrent?: boolean;
  interestIds: readonly string[];
}): ViewingKind {
  if (params.serverSaysCurrent || params.viewingNeighborhoodId === params.currentNeighborhoodId) {
    return 'current';
  }
  return params.interestIds.includes(params.viewingNeighborhoodId) ? 'interest' : 'searched';
}
