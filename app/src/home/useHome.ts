// 홈 조회 (07 SCR-01 새로고침 규칙): 화면 진입·당겨서 새로고침·보는 동네 변경·포그라운드 복귀 후 app.home-stale-after 경과.
// 백그라운드 주기 조회는 하지 않는다.
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { AppState } from 'react-native';

import { api } from '@/api';
import { getAppParams } from '@/config/appParams';
import { viewingStore, useViewingState } from '@/neighborhood/viewingStore';
import { queryKeys } from '@/query/queryKeys';
import { useSession } from '@/session/useSession';
import { generalStore } from '@/storage';

import { fetchHome } from './api';
import { homeCache } from './homeCache';

export function useHome() {
  const session = useSession();
  const viewing = useViewingState();
  const isMember = session.status === 'member';
  const neighborhoodId = viewing.viewingNeighborhoodId;
  // 비회원은 동네 ID가 필수다. 회원은 생략하면 서버가 회원의 현재 동네로 정한다(07 §1.3).
  const enabled =
    viewing.hydrated && viewing.onboardingCompleted && session.status !== 'restoring' && (neighborhoodId !== null || isMember);

  const query = useQuery({
    queryKey: queryKeys.home.byNeighborhood(neighborhoodId),
    enabled,
    queryFn: async ({ signal }) => {
      const sensitivity = isMember ? null : await generalStore.get('guestTemperatureSensitivity');
      const home = await fetchHome(api, { neighborhoodId, temperatureSensitivity: sensitivity, signal });
      await homeCache.save(home);
      return home;
    },
  });

  // 회원이 동네를 지정하지 않아 서버가 현재 동네로 정했으면 그 동네를 보는 동네로 기억한다.
  useEffect(() => {
    if (neighborhoodId === null && query.data) {
      viewingStore.setViewing(query.data.neighborhood.id).catch(() => undefined);
    }
  }, [neighborhoodId, query.data]);

  const { refetch, dataUpdatedAt } = query;
  const updatedAtRef = useRef(dataUpdatedAt);
  useEffect(() => {
    updatedAtRef.current = dataUpdatedAt;
  }, [dataUpdatedAt]);
  useEffect(() => {
    if (!enabled) {
      return;
    }
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') {
        return;
      }
      const elapsed = Date.now() - updatedAtRef.current;
      if (updatedAtRef.current === 0 || elapsed >= getAppParams().homeStaleAfterMs) {
        refetch().catch(() => undefined);
      }
    });
    return () => subscription.remove();
  }, [enabled, refetch]);

  const cached = query.data ? null : homeCache.peek(neighborhoodId);
  return {
    query,
    enabled,
    home: query.data ?? cached?.body ?? null,
    cachedSavedAt: query.data ? null : (cached?.savedAt ?? null),
  };
}
