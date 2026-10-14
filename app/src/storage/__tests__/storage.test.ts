// 07 §6.1 선언된 키만, §6.2 로그아웃·탈퇴 후 정리
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';

import { sessionStore } from '@/session/sessionStore';

import {
  clearMemberDataOnSignOut,
  CoordinateStorageError,
  generalStore,
  type GeneralKey,
  secureStore,
  type SecureKey,
  UndeclaredStorageKeyError,
} from '..';

const secureMap = (SecureStore as unknown as { __store: Map<string, string> }).__store;

beforeEach(async () => {
  await AsyncStorage.clear();
  secureMap.clear();
});

describe('저장소 키 제한', () => {
  it('선언되지 않은 키는 읽기·쓰기·삭제 모두 거부한다', async () => {
    const undeclared = 'isProfileCompleted' as GeneralKey;
    await expect(generalStore.set(undeclared, true as never)).rejects.toBeInstanceOf(UndeclaredStorageKeyError);
    await expect(generalStore.get(undeclared)).rejects.toBeInstanceOf(UndeclaredStorageKeyError);
    await expect(generalStore.remove(undeclared)).rejects.toBeInstanceOf(UndeclaredStorageKeyError);
    await expect(secureStore.set('authenticationCode' as SecureKey, {} as never)).rejects.toBeInstanceOf(
      UndeclaredStorageKeyError,
    );
    expect(await AsyncStorage.getAllKeys()).toEqual([]);
  });

  it('보안 항목은 일반 저장소로, 일반 항목은 보안 저장소로 쓸 수 없다', async () => {
    await expect(generalStore.set('authTokens' as GeneralKey, {} as never)).rejects.toBeInstanceOf(
      UndeclaredStorageKeyError,
    );
    await expect(secureStore.set('onboardingCompleted' as SecureKey, true as never)).rejects.toBeInstanceOf(
      UndeclaredStorageKeyError,
    );
  });

  it('좌표가 섞인 값은 저장하지 않는다', async () => {
    await expect(
      generalStore.set('lastHomeResponse', {
        neighborhoodId: 'n1',
        savedAt: '2026-10-15T00:00:00Z',
        body: { viewing: { location: { latitude: 37.5, longitude: 127 } } },
      }),
    ).rejects.toBeInstanceOf(CoordinateStorageError);
  });

  it('선언된 키는 값을 그대로 되돌려준다', async () => {
    await generalStore.set('guestInterestNeighborhoods', ['n1', 'n2']);
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toEqual(['n1', 'n2']);
    await expect(generalStore.get('pendingAction')).resolves.toBeNull();
  });
});

describe('토큰은 한 항목으로 함께 저장', () => {
  it('접근·재발급 토큰을 보안 저장소의 한 항목에 저장한다', async () => {
    const tokens = {
      accessToken: 'a',
      accessTokenExpiresAt: '2026-10-15T00:00:00Z',
      refreshToken: 'r',
      refreshTokenExpiresAt: '2026-11-15T00:00:00Z',
    };
    await sessionStore.saveTokens(tokens);
    expect(SecureStore.setItemAsync).toHaveBeenLastCalledWith('wt.authTokens', JSON.stringify(tokens));
    await expect(secureStore.get('authTokens')).resolves.toEqual(tokens);
    expect(sessionStore.getState().status).toBe('member');
  });

  it('재발급 실패 처리는 토큰만 지우고 초안을 남기며 배너를 한 번만 알린다', async () => {
    await sessionStore.saveTokens({
      accessToken: 'a',
      accessTokenExpiresAt: 'x',
      refreshToken: 'r',
      refreshTokenExpiresAt: 'y',
    });
    await generalStore.set('postDraft', { body: '초안', imageIds: [], feelingRecordId: null, savedAt: 'x' });

    await sessionStore.expire();

    await expect(secureStore.get('authTokens')).resolves.toBeNull();
    await expect(generalStore.get('postDraft')).resolves.toMatchObject({ body: '초안' });
    expect(sessionStore.getState().status).toBe('guest');
    expect(sessionStore.consumeReloginBanner()).toBe(true);
    expect(sessionStore.consumeReloginBanner()).toBe(false);
  });
});

describe('로그아웃·탈퇴 후 정리 (07 §6.2)', () => {
  it('회원 데이터는 지우고 첫 실행 완료·기기 ID/키는 남긴다', async () => {
    await secureStore.set('authTokens', {
      accessToken: 'a',
      accessTokenExpiresAt: 'x',
      refreshToken: 'r',
      refreshTokenExpiresAt: 'y',
    });
    await secureStore.set('deviceCredential', { deviceId: 'd1', deviceKey: 'k1' });
    await generalStore.set('onboardingCompleted', true);
    await generalStore.set('currentNeighborhood', { neighborhoodId: 'n1', resolvedAt: 'x' });
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'n1' });
    await generalStore.set('lastHomeResponse', { neighborhoodId: 'n1', savedAt: 'x', body: {} });
    await generalStore.set('postDraft', { body: '초안', imageIds: [], feelingRecordId: null, savedAt: 'x' });
    await generalStore.set('pushTokenRegistration', { pushToken: 'p' });

    await clearMemberDataOnSignOut();

    await expect(secureStore.get('authTokens')).resolves.toBeNull();
    await expect(generalStore.get('currentNeighborhood')).resolves.toBeNull();
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toBeNull();
    await expect(generalStore.get('lastHomeResponse')).resolves.toBeNull();
    await expect(generalStore.get('postDraft')).resolves.toBeNull();

    await expect(generalStore.get('onboardingCompleted')).resolves.toBe(true);
    await expect(secureStore.get('deviceCredential')).resolves.toEqual({ deviceId: 'd1', deviceKey: 'k1' });
    await expect(generalStore.get('pushTokenRegistration')).resolves.toEqual({ pushToken: 'p' });
  });
});
