// expo-router 대체(화면 단위 테스트용). 사용: jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());
import { useEffect } from 'react';

type Listener = (event: { preventDefault: () => void; data: { action: unknown } }) => void;

export const mockRouter = {
  push: jest.fn(),
  replace: jest.fn(),
  navigate: jest.fn(),
  back: jest.fn(),
  canGoBack: jest.fn(() => true),
};

const listeners = new Map<string, Set<Listener>>();

export const mockNavigation = {
  addListener: jest.fn((name: string, listener: Listener) => {
    const set = listeners.get(name) ?? new Set<Listener>();
    set.add(listener);
    listeners.set(name, set);
    return () => set.delete(listener);
  }),
  dispatch: jest.fn(),
  /** 화면을 닫으려는 시도(뒤로 가기·제스처)를 흉내 낸다. 막혔으면 true */
  emitBeforeRemove(): boolean {
    let prevented = false;
    listeners.get('beforeRemove')?.forEach((listener) =>
      listener({ preventDefault: () => (prevented = true), data: { action: { type: 'GO_BACK' } } }),
    );
    return prevented;
  },
};

export function resetRouterMock(): void {
  Object.values(mockRouter).forEach((fn) => fn.mockClear());
  mockRouter.canGoBack.mockImplementation(() => true);
  mockNavigation.addListener.mockClear();
  mockNavigation.dispatch.mockClear();
  listeners.clear();
}

export function expoRouterMock() {
  return {
    router: mockRouter,
    useRouter: () => mockRouter,
    useNavigation: () => mockNavigation,
    useFocusEffect: (effect: () => void | (() => void)) => {
      useEffect(effect, [effect]);
    },
    useLocalSearchParams: () => ({}),
    Stack: { Screen: () => null },
    Redirect: () => null,
    Link: () => null,
  };
}
