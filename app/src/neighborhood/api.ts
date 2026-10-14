// 동네 API (02 FR-NBR-01·02·05·06·07, backend NeighborhoodController·MyNeighborhoodController)
import type { ApiClient } from '@/api/client';

import {
  type CurrentNeighborhoodResponse,
  type LocationRequest,
  type MergeResponse,
  type NeighborhoodResponse,
  type NeighborhoodSummary,
  toSummary,
} from './types';

const INTEREST_PATH = '/api/v1/members/me/interest-neighborhoods';

/** 비회원: 저장 없는 좌표 판정. 판정 불가면 NEIGHBORHOOD_NOT_RESOLVABLE(422). */
export async function locateNeighborhood(api: ApiClient, location: LocationRequest): Promise<NeighborhoodSummary> {
  return toSummary(await api.post<NeighborhoodResponse>('/api/v1/neighborhoods/locate', location, { auth: false }));
}

/** 회원: 좌표 판정 + 회원의 현재 동네 갱신(FR-NBR-02). 서버도 최소 간격 안이면 저장값을 돌려준다. */
export async function refreshMemberCurrentNeighborhood(
  api: ApiClient,
  location: LocationRequest,
): Promise<NeighborhoodSummary | null> {
  const response = await api.put<CurrentNeighborhoodResponse>('/api/v1/members/me/current-neighborhood', location);
  return response.neighborhood ? toSummary(response.neighborhood) : null;
}

export async function searchNeighborhoods(
  api: ApiClient,
  keyword: string,
  signal?: AbortSignal,
): Promise<NeighborhoodSummary[]> {
  const list = await api.get<NeighborhoodResponse[]>('/api/v1/neighborhoods/search', {
    query: { q: keyword },
    auth: false,
    signal,
  });
  return list.map(toSummary);
}

export async function getNeighborhood(api: ApiClient, id: string): Promise<NeighborhoodSummary> {
  return toSummary(
    await api.get<NeighborhoodResponse>(`/api/v1/neighborhoods/${encodeURIComponent(id)}`, { auth: false }),
  );
}

/** 여러 동네를 한 번에. 서버 응답 순서는 보장되지 않아 요청한 순서로 다시 맞추고, 없는 동네는 뺀다. */
export async function getNeighborhoods(api: ApiClient, ids: readonly string[]): Promise<NeighborhoodSummary[]> {
  if (ids.length === 0) {
    return [];
  }
  const list = await api.get<NeighborhoodResponse[]>('/api/v1/neighborhoods', { query: { ids }, auth: false });
  const byId = new Map(list.map((item) => [item.id, toSummary(item)]));
  return ids.flatMap((id) => {
    const found = byId.get(id);
    return found ? [found] : [];
  });
}

export const memberInterestApi = {
  async list(api: ApiClient): Promise<NeighborhoodSummary[]> {
    return (await api.get<NeighborhoodResponse[]>(INTEREST_PATH)).map(toSummary);
  },
  async add(api: ApiClient, neighborhoodId: string): Promise<NeighborhoodSummary[]> {
    return (await api.post<NeighborhoodResponse[]>(INTEREST_PATH, { neighborhoodId })).map(toSummary);
  },
  async remove(api: ApiClient, neighborhoodId: string): Promise<NeighborhoodSummary[]> {
    return (await api.delete<NeighborhoodResponse[]>(`${INTEREST_PATH}/${encodeURIComponent(neighborhoodId)}`)).map(
      toSummary,
    );
  },
  async reorder(api: ApiClient, neighborhoodIds: readonly string[]): Promise<NeighborhoodSummary[]> {
    return (await api.put<NeighborhoodResponse[]>(`${INTEREST_PATH}/order`, { neighborhoodIds })).map(toSummary);
  },
  async merge(
    api: ApiClient,
    neighborhoodIds: readonly string[],
  ): Promise<{ interests: NeighborhoodSummary[]; skipped: string[]; discarded: string[] }> {
    const response = await api.post<MergeResponse>(`${INTEREST_PATH}/merge`, { neighborhoodIds });
    return { interests: response.interests.map(toSummary), skipped: response.skipped, discarded: response.discarded };
  },
};
