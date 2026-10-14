// 07 §2 규칙 5·6: 좌표 → 동네 ID만 남김, 비회원 locate / 회원 현재 동네 갱신, 보는 동네 따라가기
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';

import { createViewingStore } from '@/neighborhood/viewingStore';
import { generalStore } from '@/storage';
import { jsonResponse, problem, setupClient } from '@/test-utils/apiClient';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';

import { judgeCurrentNeighborhood } from '../judgeCurrentNeighborhood';

const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' };
const mockedLocation = Location as jest.Mocked<typeof Location>;

beforeEach(async () => {
  await AsyncStorage.clear();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted as never);
});

async function storedValuesText(): Promise<string> {
  const keys = await AsyncStorage.getAllKeys();
  const values = await AsyncStorage.multiGet(keys);
  return JSON.stringify(values);
}

describe('현재 동네 판정', () => {
  it('비회원은 locate로 판정하고 동네 ID·판정 시각만 저장한다(좌표 없음)', async () => {
    const { client, calls } = setupClient({ handler: () => jsonResponse(200, neighborhoodResponse('C2', '역삼2동')) });
    const store = createViewingStore();
    await store.hydrate();

    const result = await judgeCurrentNeighborhood({
      api: client,
      store,
      isMember: false,
      now: () => new Date('2026-10-15T00:00:00Z'),
    });

    expect(result).toMatchObject({ status: 'resolved', neighborhood: { id: 'C2' }, viewingNeighborhoodId: 'C2' });
    expect(calls).toHaveLength(1);
    expect(calls[0].method).toBe('POST');
    expect(calls[0].url).toBe('http://api.test/api/v1/neighborhoods/locate');
    // 좌표는 본문으로만, 쿼리에는 싣지 않는다.
    expect(calls[0].body).toEqual({ latitude: 37.5, longitude: 127.0, accuracyMeters: 30 });
    await expect(generalStore.get('currentNeighborhood')).resolves.toEqual({
      neighborhoodId: 'C2',
      resolvedAt: '2026-10-15T00:00:00.000Z',
    });
    const stored = await storedValuesText();
    expect(stored).not.toMatch(/latitude|longitude|37\.5|127/);
  });

  it('회원은 현재 동네 갱신(PUT)으로 판정해 서버의 현재 동네도 맞춘다', async () => {
    const { client, calls } = setupClient({
      handler: () =>
        jsonResponse(200, {
          neighborhood: neighborhoodResponse('C3', '삼성1동'),
          confirmedAt: '2026-10-15T00:00:00Z',
          refreshed: true,
        }),
    });
    const store = createViewingStore();
    await store.hydrate();

    const result = await judgeCurrentNeighborhood({ api: client, store, isMember: true });

    expect(result.status).toBe('resolved');
    expect(calls[0].method).toBe('PUT');
    expect(calls[0].url).toBe('http://api.test/api/v1/members/me/current-neighborhood');
  });

  it('결과가 바뀌어도 관심 동네를 보는 중이면 보는 동네를 바꾸지 않는다', async () => {
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'I1' });
    const { client } = setupClient({ handler: () => jsonResponse(200, neighborhoodResponse('C2', '역삼2동')) });
    const store = createViewingStore();
    await store.hydrate();

    await judgeCurrentNeighborhood({ api: client, store, isMember: false });

    expect(store.getState()).toMatchObject({ viewingNeighborhoodId: 'I1', current: { neighborhoodId: 'C2' } });
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toEqual({ neighborhoodId: 'I1' });
  });

  it('현재 동네를 보는 중이었으면 새 현재 동네를 따라간다', async () => {
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'C1' });
    const { client } = setupClient({ handler: () => jsonResponse(200, neighborhoodResponse('C2', '역삼2동')) });
    const store = createViewingStore();
    await store.hydrate();

    await judgeCurrentNeighborhood({ api: client, store, isMember: false });

    expect(store.getState().viewingNeighborhoodId).toBe('C2');
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toEqual({ neighborhoodId: 'C2' });
  });

  it('판정 불가면 저장된 현재 동네를 바꾸지 않는다', async () => {
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
    const { client } = setupClient({ handler: () => problem(422, 'NEIGHBORHOOD_NOT_RESOLVABLE') });
    const store = createViewingStore();
    await store.hydrate();

    const result = await judgeCurrentNeighborhood({ api: client, store, isMember: false });

    expect(result.status).toBe('failed');
    await expect(generalStore.get('currentNeighborhood')).resolves.toMatchObject({ neighborhoodId: 'C1' });
  });

  it('위치 권한이 없으면 좌표를 얻지도, 서버를 부르지도 않는다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue({
      status: 'denied',
      granted: false,
      canAskAgain: true,
      expires: 'never',
    } as never);
    mockedLocation.getCurrentPositionAsync.mockClear();
    const { client, calls } = setupClient({ handler: () => jsonResponse(200, {}) });
    const store = createViewingStore();

    const result = await judgeCurrentNeighborhood({ api: client, store, isMember: false });

    expect(result).toEqual({ status: 'no-permission' });
    expect(calls).toHaveLength(0);
    expect(mockedLocation.getCurrentPositionAsync).not.toHaveBeenCalled();
  });
});
