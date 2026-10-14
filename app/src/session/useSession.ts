import { useSyncExternalStore } from 'react';

import { type SessionState, sessionStore } from './sessionStore';

/** UI에서 회원/비회원 상태를 읽는다. */
export function useSession(): SessionState {
  return useSyncExternalStore(sessionStore.subscribe, sessionStore.getState, sessionStore.getState);
}
