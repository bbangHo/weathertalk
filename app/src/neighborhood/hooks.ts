// 동네 화면용 훅 (SCR-01 헤더, SCR-02, SCR-03)
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';

import { api } from '@/api';
import { getAppParams } from '@/config/appParams';
import { queryKeys } from '@/query/queryKeys';
import { useSession } from '@/session/useSession';

import { getNeighborhood } from './api';
import { createInterestActions, type InterestScope, loadInterests } from './interests';

export function useInterestScope(): InterestScope {
  return useSession().status === 'member' ? 'member' : 'guest';
}

export function useInterestNeighborhoods() {
  const scope = useInterestScope();
  const session = useSession();
  const queryClient = useQueryClient();
  const max = getAppParams().maxInterestNeighborhoods;
  const query = useQuery({
    queryKey: queryKeys.neighborhood.interest(scope),
    queryFn: () => loadInterests(api, scope, max),
    enabled: session.status !== 'restoring',
  });
  const actions = useMemo(() => createInterestActions({ api, queryClient, scope, max }), [queryClient, scope, max]);
  return { query, actions, max, scope };
}

/** 동네 이름 조회(현재 동네 줄 등). 동네 이름은 바뀌지 않으니 한 번 받으면 다시 부르지 않는다. */
export function useNeighborhoodDetail(neighborhoodId: string | null) {
  return useQuery({
    queryKey: queryKeys.neighborhood.detail(neighborhoodId ?? ''),
    queryFn: () => getNeighborhood(api, neighborhoodId!),
    enabled: neighborhoodId !== null,
    staleTime: Infinity,
  });
}
