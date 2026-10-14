// 서버 데이터 캐시 기본값 (07 §5 새로고침 규칙: 백그라운드 주기 조회 없음, 변경 후 무효화)
import { QueryClient } from '@tanstack/react-query';

import { isApiError, isNetworkError } from '@/api/errors';

function shouldRetry(failureCount: number, error: unknown): boolean {
  // 4xx는 다시 보내도 같다. 네트워크·5xx만 한 번 더 시도한다.
  if (isApiError(error)) {
    return error.status >= 500 && failureCount < 1;
  }
  return isNetworkError(error) && failureCount < 1;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetry,
        refetchInterval: false,
        refetchIntervalInBackground: false,
        // 포그라운드 복귀 재조회는 app.home-stale-after 기준으로 화면이 직접 정한다.
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        staleTime: 0,
      },
      mutations: {
        // 쓰기 재시도는 이용자가 "다시 시도"로 한다(같은 Idempotency-Key 재사용).
        retry: false,
      },
    },
  });
}
