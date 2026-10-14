// 화면 밖(보류 행동 자동 적용 등)에서 캐시를 갱신하려고 앱이 쓰는 QueryClient를 기억한다.
import type { QueryClient } from '@tanstack/react-query';

let active: QueryClient | null = null;

export function setActiveQueryClient(client: QueryClient | null): void {
  active = client;
}

export function getActiveQueryClient(): QueryClient | null {
  return active;
}
