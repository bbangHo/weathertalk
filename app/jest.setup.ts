// 테스트용 네이티브 모듈 대체
jest.mock('@react-native-async-storage/async-storage', () =>
  jest.requireActual('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
  };
});

jest.mock('expo-crypto', () => ({
  randomUUID: () => jest.requireActual('crypto').randomUUID() as string,
}));

jest.mock('expo-splash-screen', () => ({
  preventAutoHideAsync: jest.fn(async () => true),
  hide: jest.fn(),
  hideAsync: jest.fn(async () => {}),
  setOptions: jest.fn(),
}));

// 위치: 기본은 권한 미결정. 테스트마다 mockResolvedValue로 바꾼다. 좌표는 가짜 값이다.
jest.mock('expo-location', () => {
  const PermissionStatus = { DENIED: 'denied', GRANTED: 'granted', UNDETERMINED: 'undetermined' };
  const undetermined = { status: 'undetermined', granted: false, canAskAgain: true, expires: 'never' };
  return {
    PermissionStatus,
    Accuracy: { Lowest: 1, Low: 2, Balanced: 3, High: 4, Highest: 5, BestForNavigation: 6 },
    getForegroundPermissionsAsync: jest.fn(async () => undetermined),
    requestForegroundPermissionsAsync: jest.fn(async () => ({ ...undetermined, status: 'denied', canAskAgain: false })),
    getCurrentPositionAsync: jest.fn(async () => ({
      coords: { latitude: 37.5, longitude: 127.0, accuracy: 30, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
      timestamp: 0,
    })),
  };
});
