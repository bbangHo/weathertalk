// 필수 약관 재동의 상태 (07 §2 규칙 12, §3 규칙 2, 01 FR-MEM-12). 기기에 저장하지 않고 메모리에만 둔다(§6.1에 없음).
// 출처: 로그인 응답의 회원 요약, 회원 프로필 조회, 쓰기 요청의 TERMS_REAGREEMENT_REQUIRED.
import { useSyncExternalStore } from 'react';

import type { TermsResponse } from './api';

export type TermsStatusState = {
  reagreementRequired: boolean;
  /** 재동의할 약관. 쓰기 오류로만 알게 된 경우 비어 있을 수 있다(시트가 프로필을 다시 읽는다). */
  termsToReagree: TermsResponse[];
  /** 홈 진입 때 시트를 이미 한 번 띄웠는가(앱 실행당 1회) */
  promptedThisSession: boolean;
};

const INITIAL: TermsStatusState = { reagreementRequired: false, termsToReagree: [], promptedThisSession: false };

export function createTermsStatus() {
  let state = INITIAL;
  const listeners = new Set<() => void>();
  const setState = (patch: Partial<TermsStatusState>) => {
    state = { ...state, ...patch };
    listeners.forEach((listener) => listener());
  };
  return {
    getState: (): TermsStatusState => state,
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    /** 회원 요약·프로필에서 받은 값 */
    update(required: boolean, terms: TermsResponse[]): void {
      setState({ reagreementRequired: required, termsToReagree: required ? terms : [] });
    },
    /** 쓰기 요청이 TERMS_REAGREEMENT_REQUIRED로 막혔다 */
    markRequired(): void {
      setState({ reagreementRequired: true });
    },
    markPrompted(): void {
      setState({ promptedThisSession: true });
    },
    agreed(): void {
      setState({ reagreementRequired: false, termsToReagree: [] });
    },
    reset(): void {
      setState(INITIAL);
    },
  };
}

export type TermsStatus = ReturnType<typeof createTermsStatus>;

export const termsStatus = createTermsStatus();

export function useTermsStatus(): TermsStatusState {
  return useSyncExternalStore(termsStatus.subscribe, termsStatus.getState, termsStatus.getState);
}
