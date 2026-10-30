// 앱인토스 빌드 전용 파일(*.ait.ts)이 스토어 앱 파일과 같은 약속을 지키는지. 토스 SDK는 가짜로 대신한다.
// jest는 *.ait.ts를 자동으로 고르지 않으므로 경로를 직접 적어 불러온다.
import { jsonResponse, setupClient } from '@/test-utils/apiClient';

import { PROVIDER_BUTTONS } from '../../auth/providerLogin.ait';
import { getLocationForJudgement, getLocationPermission, requestLocationPermission } from '../../location/deviceLocation.ait';
import { deleteItemAsync, getItemAsync, setItemAsync } from '../../storage/secureBackend.ait';

jest.mock('@apps-in-toss/web-framework', () => {
  const storage = new Map<string, string>();
  return {
    __storage: storage,
    Accuracy: { Lowest: 1, Low: 2, Balanced: 3, High: 4, Highest: 5, BestForNavigation: 6 },
    Storage: {
      getItem: async (key: string) => storage.get(key) ?? null,
      setItem: async (key: string, value: string) => {
        storage.set(key, value);
      },
      removeItem: async (key: string) => {
        storage.delete(key);
      },
    },
    Device: { getLocation: Object.assign(jest.fn(), { getPermission: jest.fn(), openPermissionDialog: jest.fn() }) },
    User: { createAnonymousKeyAuthCode: jest.fn(async () => ({ code: 'auth-code-1', expiresIn: 300 })) },
  };
});

const sdk = jest.requireMock('@apps-in-toss/web-framework');
const mockStorage: Map<string, string> = sdk.__storage;
const mockGetLocation = sdk.Device.getLocation as jest.Mock & { getPermission: jest.Mock; openPermissionDialog: jest.Mock };

describe('보안 저장소(앱인토스 Storage)', () => {
  it('저장·읽기·지우기를 토스 저장소로 한다', async () => {
    await setItemAsync('wt.authTokens', '{"a":1}');
    expect(mockStorage.get('wt.authTokens')).toBe('{"a":1}');
    expect(await getItemAsync('wt.authTokens')).toBe('{"a":1}');
    await deleteItemAsync('wt.authTokens');
    expect(await getItemAsync('wt.authTokens')).toBeNull();
  });
});

describe('위치(토스 권한·좌표)', () => {
  it('토스 권한 상태를 앱 권한 값으로 바꾼다', async () => {
    mockGetLocation.getPermission.mockResolvedValueOnce('allowed');
    expect(await getLocationPermission()).toEqual({ permission: 'granted', canAskAgain: true });
    mockGetLocation.getPermission.mockResolvedValueOnce('notDetermined');
    expect((await getLocationPermission()).permission).toBe('undetermined');
    mockGetLocation.openPermissionDialog.mockResolvedValueOnce('denied');
    expect((await requestLocationPermission()).permission).toBe('denied');
  });

  it('권한 확인이 실패하면 권한 없음으로 본다', async () => {
    mockGetLocation.getPermission.mockRejectedValueOnce(new Error('bridge'));
    expect((await getLocationPermission()).permission).toBe('undetermined');
    mockGetLocation.openPermissionDialog.mockRejectedValueOnce(new Error('bridge'));
    expect((await requestLocationPermission()).permission).toBe('denied');
  });

  it('좌표는 coords에서 꺼내고 이상한 정확도는 버린다', async () => {
    mockGetLocation.mockResolvedValueOnce({ timestamp: 0, coords: { latitude: 37.5, longitude: 127.03, accuracy: 120 } });
    expect(await getLocationForJudgement()).toEqual({ latitude: 37.5, longitude: 127.03, accuracyMeters: 120 });
    expect(mockGetLocation).toHaveBeenLastCalledWith({ accuracy: 3 });
    mockGetLocation.mockResolvedValueOnce({ timestamp: 0, coords: { latitude: 1, longitude: 2, accuracy: -1 } });
    expect((await getLocationForJudgement()).accuracyMeters).toBeNull();
  });
});

describe('로그인 수단(토스 식별키)', () => {
  it('버튼은 토스 하나뿐이고, 받은 인증 코드를 TOSS_ANON 로그인으로 보낸다(식별키 값은 보내지 않음)', async () => {
    expect(PROVIDER_BUTTONS.map((b) => b.title)).toEqual(['토스로 계속하기']);
    const { client, calls } = setupClient({
      handler: () => jsonResponse(200, { registered: false, tokens: null, member: null, signupTicket: null, terms: null }),
    });

    await PROVIDER_BUTTONS[0].login!(client);

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('http://api.test/api/v1/auth/login');
    expect(calls[0].body).toEqual({ provider: 'TOSS_ANON', token: 'auth-code-1' });
    expect(calls[0].headers.Authorization).toBeUndefined();
  });
});
