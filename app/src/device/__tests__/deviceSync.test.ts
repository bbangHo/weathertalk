// 07 §2-11 기기 확인: 실패해도 화면을 막지 않는다
import * as SecureStore from 'expo-secure-store';

import { secureStore } from '@/storage';
import { jsonResponse, problem, setupClient } from '@/test-utils/apiClient';

import { syncDevice } from '../deviceSync';

const secureMap = (SecureStore as unknown as { __store: Map<string, string> }).__store;

beforeEach(() => {
  secureMap.clear();
});

describe('syncDevice', () => {
  it('푸시 토큰 없이 권한 UNKNOWN으로 등록하고 기기 키를 보관한다', async () => {
    const { client, calls } = setupClient({
      handler: () =>
        jsonResponse(200, {
          deviceId: '00000000-0000-4000-8000-000000000001',
          deviceKey: 'key-1',
          registered: true,
          status: 'ACTIVE',
          linkedToMember: false,
          commentAlertEnabled: false,
        }),
    });

    const response = await syncDevice(client);

    expect(response?.registered).toBe(true);
    expect(calls[0].body).toEqual({
      deviceId: null,
      deviceKey: null,
      platform: 'IOS',
      pushToken: null,
      osPermission: 'UNKNOWN',
    });
    await expect(secureStore.get('deviceCredential')).resolves.toEqual({
      deviceId: '00000000-0000-4000-8000-000000000001',
      deviceKey: 'key-1',
    });
  });

  it('서버 오류·네트워크 오류는 던지지 않고 null', async () => {
    const failing = setupClient({ handler: () => problem(500, 'INTERNAL_ERROR') });
    await expect(syncDevice(failing.client)).resolves.toBeNull();

    const offline = setupClient({
      handler: () => {
        throw new TypeError('Network request failed');
      },
    });
    await expect(syncDevice(offline.client)).resolves.toBeNull();
  });
});
