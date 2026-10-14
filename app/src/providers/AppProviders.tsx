// 앱 전역 제공자: 서버 데이터 캐시, 안전 영역, 세션 복원 (07 §2)
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { type ReactNode, useEffect, useState } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { setActiveQueryClient } from '@/query/activeClient';
import { createQueryClient } from '@/query/queryClient';
import { sessionStore } from '@/session/sessionStore';

export function AppProviders({ children, queryClient }: { children: ReactNode; queryClient?: QueryClient }) {
  const [client] = useState(() => queryClient ?? createQueryClient());

  useEffect(() => {
    setActiveQueryClient(client);
    let previous = sessionStore.getState().status;
    const unsubscribe = sessionStore.subscribe(() => {
      const next = sessionStore.getState().status;
      // 회원↔비회원이 바뀌면 이전 요청자의 응답을 버린다(공용 기기 보호).
      if (previous !== 'restoring' && next !== previous) {
        client.clear();
      }
      previous = next;
    });
    if (sessionStore.getState().status === 'restoring') {
      sessionStore.restore().catch(() => sessionStore.becomeGuest());
    }
    return () => {
      unsubscribe();
      setActiveQueryClient(null);
    };
  }, [client]);

  return (
    <SafeAreaProvider>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </SafeAreaProvider>
  );
}
