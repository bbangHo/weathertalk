// 07 §2 규칙 5·6 + ADR 0009: 기기 안 판정, 자동 판정은 제안만(보는 동네·현재 동네·서버 그대로), 확인한 뒤에만 적용·전송
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';

import { createViewingStore } from '@/neighborhood/viewingStore';
import { generalStore } from '@/storage';
import { jsonResponse, setupClient } from '@/test-utils/apiClient';
import { installFetch } from '@/test-utils/globalFetch';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';

import { adoptNeighborhood, judgeAndSuggest, judgeCurrentNeighborhood } from '../judgeCurrentNeighborhood';

const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' };
const mockedLocation = Location as jest.Mocked<typeof Location>;
/** jest.setup의 기본 모의 좌표(37.5, 127.0)가 속한 동네 */
const BANPO2 = '1165057000';
const NOW = () => new Date('2026-10-15T00:00:00Z');

beforeEach(async () => {
  await AsyncStorage.clear();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted as never);
});

async function storedValuesText(): Promise<string> {
  const keys = await AsyncStorage.getAllKeys();
  const values = await AsyncStorage.multiGet(keys);
  return JSON.stringify(values);
}

async function storeWith(current: string | null, viewing: string | null) {
  if (current) {
    await generalStore.set('currentNeighborhood', { neighborhoodId: current, resolvedAt: '2026-10-14T00:00:00Z' });
  }
  if (viewing) {
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: viewing });
  }
  const store = createViewingStore();
  await store.hydrate();
  return store;
}

describe('현재 동네 판정', () => {
  it('기기 안에서 판정하고 아무것도 저장·전송하지 않는다', async () => {
    const { calls } = installFetch(() => jsonResponse(200, {}));

    const result = await judgeCurrentNeighborhood();

    expect(result).toMatchObject({
      status: 'resolved',
      neighborhood: { id: BANPO2, fullName: '서울특별시 서초구 반포2동', shortName: '서초구 반포2동' },
    });
    expect(calls).toHaveLength(0);
    expect(await storedValuesText()).toBe('[]');
  });

  it('판정 불가(바다 위)면 실패', async () => {
    await expect(judgeCurrentNeighborhood({ location: { latitude: 36.0, longitude: 124.0, accuracyMeters: 20 } })).resolves
      .toMatchObject({ status: 'failed' });
  });

  it('위치 권한이 없으면 좌표를 얻지 않는다', async () => {
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue({ ...granted, status: 'denied', granted: false } as never);
    mockedLocation.getCurrentPositionAsync.mockClear();

    await expect(judgeCurrentNeighborhood()).resolves.toEqual({ status: 'no-permission' });
    expect(mockedLocation.getCurrentPositionAsync).not.toHaveBeenCalled();
  });
});

describe('자동 판정은 제안만 한다', () => {
  it('보는 동네와 다르면 제안만 두고 보는 동네·현재 동네·서버는 그대로', async () => {
    const store = await storeWith('C1', 'C1');
    const { calls } = installFetch(() => jsonResponse(200, {}));

    await judgeAndSuggest(store, NOW);

    expect(store.getState()).toMatchObject({
      viewingNeighborhoodId: 'C1',
      current: { neighborhoodId: 'C1', resolvedAt: '2026-10-14T00:00:00Z' },
      suggestion: { id: BANPO2, eupmyeondongName: '반포2동' },
    });
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toEqual({ neighborhoodId: 'C1' });
    await expect(generalStore.get('currentNeighborhood')).resolves.toMatchObject({ neighborhoodId: 'C1' });
    expect(calls).toHaveLength(0);
  });

  it('보는 동네와 같으면 제안 없이 판정 시각만 남긴다', async () => {
    const store = await storeWith('C1', BANPO2);

    await judgeAndSuggest(store, NOW);

    expect(store.getState()).toMatchObject({
      viewingNeighborhoodId: BANPO2,
      current: { neighborhoodId: BANPO2, resolvedAt: '2026-10-15T00:00:00.000Z' },
      suggestion: null,
    });
  });

  it('제안을 닫으면 판정 결과를 버린다', async () => {
    const store = await storeWith('C1', 'C1');
    await judgeAndSuggest(store, NOW);

    store.dismissSuggestion();

    expect(store.getState()).toMatchObject({ suggestion: null, viewingNeighborhoodId: 'C1', current: { neighborhoodId: 'C1' } });
  });
});

describe('확인한 동네 적용', () => {
  it('회원: 현재·보는 동네를 바꾸고 동네 ID로만 서버 현재 동네를 맞춘다', async () => {
    const store = await storeWith('C1', 'I1');
    await judgeAndSuggest(store, NOW);
    const { client, calls } = setupClient({
      handler: () => jsonResponse(200, { neighborhood: neighborhoodResponse(BANPO2, '반포2동'), confirmedAt: null }),
    });

    await adoptNeighborhood(store, BANPO2, { api: client }, NOW);

    expect(store.getState()).toMatchObject({
      viewingNeighborhoodId: BANPO2,
      current: { neighborhoodId: BANPO2 },
      suggestion: null,
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ method: 'PUT', url: 'http://api.test/api/v1/members/me/current-neighborhood' });
    expect(calls[0].body).toEqual({ neighborhoodId: BANPO2 });
  });

  it('비회원: 기기에만 반영한다', async () => {
    const store = await storeWith(null, null);
    const { calls } = installFetch(() => jsonResponse(200, {}));

    await adoptNeighborhood(store, BANPO2, null, NOW);

    expect(store.getState().viewingNeighborhoodId).toBe(BANPO2);
    expect(calls).toHaveLength(0);
  });
});
