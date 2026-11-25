// 07 §2 규칙 5·6: 좌표 → 동네 ID만 남김, 기기 안 판정(서버 호출 없음, ADR 0009), 보는 동네 따라가기
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';

import { createViewingStore } from '@/neighborhood/viewingStore';
import { generalStore } from '@/storage';
import { jsonResponse } from '@/test-utils/apiClient';
import { installFetch } from '@/test-utils/globalFetch';

import { judgeCurrentNeighborhood } from '../judgeCurrentNeighborhood';

const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' };
const mockedLocation = Location as jest.Mocked<typeof Location>;
/** jest.setup의 기본 모의 좌표(37.5, 127.0)가 속한 동네 */
const BANPO2 = '1165057000';

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
  it('기기 안에서 판정하고 동네 ID·판정 시각만 저장한다(서버 호출·좌표 저장 없음)', async () => {
    const { calls } = installFetch(() => jsonResponse(200, {}));
    const store = createViewingStore();
    await store.hydrate();

    const result = await judgeCurrentNeighborhood({ store, now: () => new Date('2026-10-15T00:00:00Z') });

    expect(result).toMatchObject({
      status: 'resolved',
      neighborhood: { id: BANPO2, fullName: '서울특별시 서초구 반포2동', shortName: '서초구 반포2동' },
      viewingNeighborhoodId: BANPO2,
    });
    expect(calls).toHaveLength(0);
    await expect(generalStore.get('currentNeighborhood')).resolves.toEqual({
      neighborhoodId: BANPO2,
      resolvedAt: '2026-10-15T00:00:00.000Z',
    });
    const stored = await storedValuesText();
    expect(stored).not.toMatch(/latitude|longitude|37\.5|127\.0/);
  });

  it('결과가 바뀌어도 관심 동네를 보는 중이면 보는 동네를 바꾸지 않는다', async () => {
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'I1' });
    const store = createViewingStore();
    await store.hydrate();

    await judgeCurrentNeighborhood({ store });

    expect(store.getState()).toMatchObject({ viewingNeighborhoodId: 'I1', current: { neighborhoodId: BANPO2 } });
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toEqual({ neighborhoodId: 'I1' });
  });

  it('현재 동네를 보는 중이었으면 새 현재 동네를 따라간다', async () => {
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'C1' });
    const store = createViewingStore();
    await store.hydrate();

    await judgeCurrentNeighborhood({ store });

    expect(store.getState().viewingNeighborhoodId).toBe(BANPO2);
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toEqual({ neighborhoodId: BANPO2 });
  });

  it('판정 불가(바다 위)면 저장된 현재 동네를 바꾸지 않는다', async () => {
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' });
    const store = createViewingStore();
    await store.hydrate();

    const result = await judgeCurrentNeighborhood({
      store,
      location: { latitude: 36.0, longitude: 124.0, accuracyMeters: 20 },
    });

    expect(result.status).toBe('failed');
    await expect(generalStore.get('currentNeighborhood')).resolves.toMatchObject({ neighborhoodId: 'C1' });
  });

  it('위치 권한이 없으면 좌표를 얻지 않는다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue({
      status: 'denied',
      granted: false,
      canAskAgain: true,
      expires: 'never',
    } as never);
    mockedLocation.getCurrentPositionAsync.mockClear();
    const store = createViewingStore();

    const result = await judgeCurrentNeighborhood({ store });

    expect(result).toEqual({ status: 'no-permission' });
    expect(mockedLocation.getCurrentPositionAsync).not.toHaveBeenCalled();
  });
});
