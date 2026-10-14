// 홈 조합 조회 (07 §1, §10~12 구현 메모, backend home/internal/web/HomeController)
import type { ApiClient } from '@/api/client';
import type { TemperatureSensitivity } from '@/storage';

import type { HomeView } from './types';

export const HOME_PATH = '/api/v1/home';

export type HomeRequest = {
  /** 비회원은 필수, 회원은 없으면 회원의 현재 동네 */
  neighborhoodId: string | null;
  /** 비회원 기기 민감도. 회원이면 서버가 무시하므로 보내지 않는다. */
  temperatureSensitivity: TemperatureSensitivity | null;
  signal?: AbortSignal;
};

/** 기기 헤더(X-Device-Id·Key)는 외출 시각 섹션용으로 함께 보낸다(07 §12). */
export function fetchHome(api: ApiClient, request: HomeRequest): Promise<HomeView> {
  return api.get<HomeView>(HOME_PATH, {
    query: {
      neighborhoodId: request.neighborhoodId,
      temperatureSensitivity: request.temperatureSensitivity,
    },
    device: true,
    signal: request.signal,
  });
}
