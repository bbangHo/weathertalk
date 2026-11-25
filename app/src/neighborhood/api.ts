// 동네 API (02 FR-NBR-03·05·06·07, backend NeighborhoodController·MyNeighborhoodController). 좌표 판정은 기기 안에서 한다(ADR 0009).
import type { ApiClient } from '@/api/client';

import {
  type CurrentNeighborhoodResponse,
  type MergeResponse,
  type NeighborhoodResponse,
  type NeighborhoodSummary,
  toSummary,
} from './types';

const INTEREST_PATH = '/api/v1/members/me/interest-neighborhoods';

/**
 * 회원: 사용자가 확인한 동네를 서버의 현재 동네로 저장(FR-NBR-03). 좌표 대신 기기가 판정한 동네 ID만 보낸다.
 * 없는·폐지된 동네면 CURRENT_NEIGHBORHOOD_UNVERIFIED(422).
 */
export async function setMemberCurrentNeighborhood(api: ApiClient, neighborhoodId: string): Promise<NeighborhoodSummary | null> {
  const response = await api.put<CurrentNeighborhoodResponse>('/api/v1/members/me/current-neighborhood', {
    neighborhoodId,
  });
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
