// 관심 동네 (07 SCR-02, §6.1·§6.2, 02 FR-NBR-05·06, BR-NBR-08)
// 회원은 서버(neighborhood), 비회원은 기기 저장소에만 둔다 — 비회원 목록은 서버로 보내지 않는다(가입·로그인 때 이전만).
import type { QueryClient } from '@tanstack/react-query';

import type { ApiClient } from '@/api/client';
import { isApiError } from '@/api/errors';
import { queryKeys } from '@/query/queryKeys';
import { generalStore } from '@/storage';

import { getNeighborhoods, memberInterestApi } from './api';
import { addInterestId, InterestLimitError, moveInterestId, normalizeInterestIds, removeInterestId } from './interestList';
import type { NeighborhoodSummary } from './types';

export type InterestScope = 'member' | 'guest';

export const guestInterestStore = {
  async ids(max: number): Promise<string[]> {
    return normalizeInterestIds(await generalStore.get('guestInterestNeighborhoods'), max);
  },
  async save(ids: string[]): Promise<void> {
    await generalStore.set('guestInterestNeighborhoods', ids);
  },
};

/** 관심 동네 목록(순서대로). 비회원은 기기에 남은 ID로 이름만 조회한다. */
export async function loadInterests(api: ApiClient, scope: InterestScope, max: number): Promise<NeighborhoodSummary[]> {
  if (scope === 'member') {
    return memberInterestApi.list(api);
  }
  return getNeighborhoods(api, await guestInterestStore.ids(max));
}

export type InterestChangeResult = { ok: true } | { ok: false; reason: 'limit' | 'failed'; message: string };

const LIMIT_CODE = 'INTEREST_NEIGHBORHOOD_LIMIT_EXCEEDED';

/**
 * 관심 동네 변경. 화면 목록을 먼저 바꾸고, 저장이 실패하면 원래대로 되돌린 뒤 실패를 돌려준다(SCR-02 회원 저장 실패).
 * 알림 표시는 호출한 화면이 한다.
 */
export function createInterestActions(deps: {
  api: ApiClient;
  queryClient: QueryClient;
  scope: InterestScope;
  max: number;
}) {
  const { api, queryClient, scope, max } = deps;
  const key = queryKeys.neighborhood.interest(scope);

  const current = () =>
    queryClient.ensureQueryData({ queryKey: key, queryFn: () => loadInterests(api, scope, max) });

  async function apply(
    previous: NeighborhoodSummary[],
    next: NeighborhoodSummary[],
    persist: () => Promise<NeighborhoodSummary[] | void>,
  ): Promise<InterestChangeResult> {
    queryClient.setQueryData(key, next);
    try {
      const saved = await persist();
      if (saved) {
        queryClient.setQueryData(key, saved);
      }
      return { ok: true };
    } catch (error) {
      queryClient.setQueryData(key, previous);
      if (error instanceof InterestLimitError || isApiError(error) && error.code === LIMIT_CODE) {
        return { ok: false, reason: 'limit', message: `관심 동네는 ${max}개까지 추가할 수 있어요.` };
      }
      const detail = isApiError(error) && error.detail ? error.detail : null;
      return { ok: false, reason: 'failed', message: detail ?? '저장하지 못했어요. 잠시 후 다시 시도해 주세요.' };
    }
  }

  async function load(): Promise<NeighborhoodSummary[] | InterestChangeResult> {
    try {
      return await current();
    } catch {
      return { ok: false, reason: 'failed', message: '관심 동네를 불러오지 못했어요.' };
    }
  }

  return {
    async add(neighborhood: NeighborhoodSummary): Promise<InterestChangeResult> {
      const previous = await load();
      if (!Array.isArray(previous)) {
        return previous;
      }
      if (previous.some((item) => item.id === neighborhood.id)) {
        return { ok: true };
      }
      if (previous.length >= max) {
        return { ok: false, reason: 'limit', message: `관심 동네는 ${max}개까지 추가할 수 있어요.` };
      }
      return apply(previous, [...previous, neighborhood], async () => {
        if (scope === 'member') {
          return memberInterestApi.add(api, neighborhood.id);
        }
        await guestInterestStore.save(addInterestId(await guestInterestStore.ids(max), neighborhood.id, max));
      });
    },

    async remove(neighborhoodId: string): Promise<InterestChangeResult> {
      const previous = await load();
      if (!Array.isArray(previous)) {
        return previous;
      }
      return apply(
        previous,
        previous.filter((item) => item.id !== neighborhoodId),
        async () => {
          if (scope === 'member') {
            return memberInterestApi.remove(api, neighborhoodId);
          }
          await guestInterestStore.save(removeInterestId(await guestInterestStore.ids(max), neighborhoodId));
        },
      );
    },

    async move(neighborhoodId: string, delta: -1 | 1): Promise<InterestChangeResult> {
      const previous = await load();
      if (!Array.isArray(previous)) {
        return previous;
      }
      const order = moveInterestId(
        previous.map((item) => item.id),
        neighborhoodId,
        delta,
      );
      const byId = new Map(previous.map((item) => [item.id, item]));
      const next = order.map((id) => byId.get(id)!);
      return apply(previous, next, async () => {
        if (scope === 'member') {
          return memberInterestApi.reorder(api, order);
        }
        // 기기 목록에는 이름 조회가 안 된 동네가 섞여 있을 수 있어 기기 목록 기준으로 같은 이동을 한다.
        await guestInterestStore.save(moveInterestId(await guestInterestStore.ids(max), neighborhoodId, delta));
      });
    },
  };
}

export type InterestActions = ReturnType<typeof createInterestActions>;

export type MergeOutcome =
  | { status: 'nothing' }
  | { status: 'merged'; interests: NeighborhoodSummary[]; skipped: string[]; discarded: string[] }
  | { status: 'failed'; error: unknown };

/**
 * 가입·로그인 직후 기기 보관 관심 동네를 회원 관심 동네로 이전(§6.2, FR-NBR-06). A2가 로그인 완료 후 부른다.
 * 서버가 기존 목록 뒤에 순서대로 붙이고 넘친 것은 discarded로 알려준다 — 화면은 이를 이용자에게 알린다.
 * 성공했을 때만 기기 목록을 지우고, 실패하면 남겨 다음 실행 때 다시 시도한다(로그인 완료를 막지 않는다).
 */
export async function mergeGuestInterests(api: ApiClient, max: number): Promise<MergeOutcome> {
  const ids = await guestInterestStore.ids(max);
  if (ids.length === 0) {
    return { status: 'nothing' };
  }
  try {
    const result = await memberInterestApi.merge(api, ids);
    await generalStore.remove('guestInterestNeighborhoods');
    return { status: 'merged', ...result };
  } catch (error) {
    return { status: 'failed', error };
  }
}
