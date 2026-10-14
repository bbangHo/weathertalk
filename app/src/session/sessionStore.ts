// 회원/비회원 상태와 토큰 메모리 사본 (07 §2 진입 흐름, 규칙 7·8)
import { type AuthTokens, clearTokensOnRefreshFailure, secureStore } from '@/storage';

export type SessionStatus = 'restoring' | 'member' | 'guest';

export type SessionState = {
  status: SessionStatus;
  /** "다시 로그인이 필요해요" 배너를 한 번 보여줘야 하는지 (07 §2-8). 기기에 저장하지 않는다. */
  reloginBannerPending: boolean;
};

type Listener = () => void;

/**
 * 토큰은 보안 저장소가 정본이고 여기 메모리 사본은 요청마다 저장소를 읽지 않으려고 둔다.
 * 토큰 쓰기는 반드시 이 저장소를 거쳐 메모리·보안 저장소가 함께 바뀌게 한다.
 */
export function createSessionStore() {
  let state: SessionState = { status: 'restoring', reloginBannerPending: false };
  let tokens: AuthTokens | null = null;
  const listeners = new Set<Listener>();

  function setState(patch: Partial<SessionState>) {
    state = { ...state, ...patch };
    listeners.forEach((listener) => listener());
  }

  return {
    getState: (): SessionState => state,
    subscribe(listener: Listener): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    /** 앱 시작 때 보안 저장소에서 토큰을 읽는다. 토큰 유효성 확인(요청자 확인)은 첫 API 호출이 맡는다. */
    async restore(): Promise<void> {
      tokens = await secureStore.get('authTokens');
      setState({ status: tokens ? 'member' : 'guest' });
    },

    getTokens: (): AuthTokens | null => tokens,

    /** 로그인·가입·재발급 성공: 새 접근·재발급 토큰을 한 항목으로 함께 저장한다. */
    async saveTokens(next: AuthTokens): Promise<void> {
      await secureStore.set('authTokens', next);
      tokens = next;
      setState({ status: 'member' });
    },

    /** 재발급 실패: 토큰 삭제 → 비회원 전환 → 배너 1회 (07 §2-8) */
    async expire(): Promise<void> {
      tokens = null;
      try {
        await clearTokensOnRefreshFailure();
      } finally {
        setState({ status: 'guest', reloginBannerPending: true });
      }
    },

    /** 로그아웃·탈퇴 후 상태 전환. 기기 정리는 storage의 clearMemberDataOnSignOut가 한다. */
    becomeGuest(): void {
      tokens = null;
      setState({ status: 'guest' });
    },

    /** 배너를 보여준 쪽이 호출한다. 한 번만 true를 돌려준다. */
    consumeReloginBanner(): boolean {
      if (!state.reloginBannerPending) {
        return false;
      }
      setState({ reloginBannerPending: false });
      return true;
    },
  };
}

export type SessionStore = ReturnType<typeof createSessionStore>;

export const sessionStore = createSessionStore();
