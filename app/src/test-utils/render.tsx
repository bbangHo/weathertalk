// 화면 테스트 공용 렌더러: 서버 데이터 캐시 + 안전 영역(rerender에도 유지되게 wrapper로 감싼다)
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react-native';
import type { ReactElement, ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

export function testQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      // gcTime Infinity: 테스트가 끝난 뒤 캐시 정리 타이머가 남지 않게 한다.
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
}

const METRICS = { frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 0, left: 0, right: 0, bottom: 0 } };

export function renderWithClient(ui: ReactElement, client: QueryClient = testQueryClient()) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <SafeAreaProvider initialMetrics={METRICS}>{children}</SafeAreaProvider>
    </QueryClientProvider>
  );
  return { ...render(ui, { wrapper }), client };
}
