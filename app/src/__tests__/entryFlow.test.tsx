// 07 §2 진입 흐름 + SCR-01·02·03 화면 흐름 (실제 src/app 라우트, 전역 fetch·위치는 가짜)
import AsyncStorage from '@react-native-async-storage/async-storage';
import { fireEvent, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import { renderRouter, screen, testRouter } from 'expo-router/testing-library';

import { homeCache } from '@/home/homeCache';
import { viewingStore } from '@/neighborhood/viewingStore';
import { generalStore } from '@/storage';
import { type FakeCall, jsonResponse, problem } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { homeView, neighborhoodResponse } from '@/test-utils/homeFixtures';

const APP_DIR = 'src/app';
jest.setTimeout(30000);

// 기기 안 동네 판정은 이 테스트의 가상 동네 N1(역삼1동)로 둔다 — 좌표도 판정 결과도 서버로 보내지 않는다.
jest.mock('@/location/localNeighborhood', () => ({
  locateLocally: () => ({
    id: 'N1',
    sidoName: '서울특별시',
    sigunguName: '강남구',
    eupmyeondongName: '역삼1동',
    fullName: '서울특별시 강남구 역삼1동',
    shortName: '강남구 역삼1동',
  }),
}));

const mockedLocation = Location as jest.Mocked<typeof Location>;
const permission = (status: 'granted' | 'denied' | 'undetermined', canAskAgain = true) =>
  ({ status, granted: status === 'granted', canAskAgain, expires: 'never' }) as never;

type Route = (call: FakeCall, url: URL) => Response | undefined;

/** 경로별 가짜 서버. 정해지지 않은 요청(기기 확인 등)은 503 */
function server(route: Route) {
  return installFetch((call) => {
    const url = new URL(call.url);
    return route(call, url) ?? problem(503, 'TEMPORARILY_UNAVAILABLE');
  });
}

beforeEach(async () => {
  await AsyncStorage.clear();
  viewingStore.reset();
  homeCache.clearMemory();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(permission('undetermined'));
  mockedLocation.requestForegroundPermissionsAsync.mockReset();
});

async function completedOnboarding(viewingId?: string) {
  await generalStore.set('onboardingCompleted', true);
  if (viewingId) {
    await generalStore.set('lastViewedNeighborhood', { neighborhoodId: viewingId });
  }
}

describe('첫 실행 (SCR-12)', () => {
  it('첫 실행이면 안내로 가고, "나중에"는 권한을 묻지 않고 동네 검색으로 보낸다', async () => {
    server(() => undefined);
    await renderRouter(APP_DIR);

    await waitFor(() => expect(screen).toHavePathname('/welcome'));
    expect(screen.getByText('위치는 동네 판정에만 쓰고 저장하지 않아요')).toBeTruthy();

    fireEvent.press(screen.getByTestId('welcome-later'));

    await waitFor(() => expect(screen).toHavePathname('/neighborhood-search'));
    expect(mockedLocation.requestForegroundPermissionsAsync).not.toHaveBeenCalled();
    await expect(generalStore.get('onboardingCompleted')).resolves.toBe(true);
    await expect(generalStore.get('permissionPrepromptHistory')).resolves.toHaveProperty('locationDeclinedAt');
  });

  it('"위치 사용하기" → 권한 허용 → 판정한 현재 동네로 홈', async () => {
    mockedLocation.requestForegroundPermissionsAsync.mockResolvedValue(permission('granted'));
    const { calls } = server((call, url) => {
      if (url.pathname === '/api/v1/home') {
        return jsonResponse(200, homeView());
      }
      return undefined;
    });
    mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(permission('granted'));
    await renderRouter(APP_DIR);
    await waitFor(() => expect(screen).toHavePathname('/welcome'));

    fireEvent.press(screen.getByTestId('welcome-use-location'));

    await waitFor(() => expect(screen).toHavePathname('/'));
    expect(await screen.findByText('역삼1동')).toBeTruthy();
    expect(screen.getByText('현재 동네')).toBeTruthy();
    const homeCall = calls.find((call) => pathOf(call) === '/api/v1/home');
    expect(new URL(homeCall!.url).searchParams.get('neighborhoodId')).toBe('N1');
    await expect(generalStore.get('currentNeighborhood')).resolves.toMatchObject({ neighborhoodId: 'N1' });
    expect(calls.map(pathOf)).not.toContain('/api/v1/members/me/current-neighborhood');
  });

  it('권한을 거부하면 동네 검색으로', async () => {
    mockedLocation.requestForegroundPermissionsAsync.mockResolvedValue(permission('denied', false));
    server(() => undefined);
    await renderRouter(APP_DIR);
    await waitFor(() => expect(screen).toHavePathname('/welcome'));

    fireEvent.press(screen.getByTestId('welcome-use-location'));

    await waitFor(() => expect(screen).toHavePathname('/neighborhood-search'));
  });
});

describe('홈 (SCR-01)', () => {
  it('보는 동네를 정할 수 없으면(422) 동네 검색으로 보내고 저장된 보던 동네를 지운다', async () => {
    await completedOnboarding('GONE');
    server((_call, url) =>
      url.pathname === '/api/v1/home'
        ? problem(422, 'VIEWING_NEIGHBORHOOD_UNRESOLVED', { reason: 'NOT_FOUND' })
        : undefined,
    );
    await renderRouter(APP_DIR);

    await waitFor(() => expect(screen).toHavePathname('/neighborhood-search'));
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toBeNull();
  });

  it('비회원 요청에는 동네 ID·기기 민감도를 싣는다', async () => {
    await completedOnboarding('N1');
    await generalStore.set('guestTemperatureSensitivity', 'COLD_SENSITIVE');
    const { calls } = server((_call, url) => (url.pathname === '/api/v1/home' ? jsonResponse(200, homeView()) : undefined));
    await renderRouter(APP_DIR);

    expect(await screen.findByText('역삼1동')).toBeTruthy();
    const params = new URL(calls.find((call) => pathOf(call) === '/api/v1/home')!.url).searchParams;
    expect(params.get('neighborhoodId')).toBe('N1');
    expect(params.get('temperatureSensitivity')).toBe('COLD_SENSITIVE');
    // 성공한 응답은 마지막 홈 응답으로 남는다
    await waitFor(async () => expect(await generalStore.get('lastHomeResponse')).toMatchObject({ neighborhoodId: 'N1' }));
  });

  it('조회가 실패해도 같은 동네의 마지막 홈 응답을 관측 시각과 함께 보여준다', async () => {
    await completedOnboarding('N1');
    await generalStore.set('lastHomeResponse', { neighborhoodId: 'N1', savedAt: '2026-10-15T00:30:00Z', body: homeView() });
    server(() => undefined);
    await renderRouter(APP_DIR);

    expect(await screen.findByText('역삼1동')).toBeTruthy();
    expect(screen.getByTestId('observation-time').props.children).toBe('09:00 기준');
    // 5xx는 한 번 재시도한 뒤 실패로 본다(queryClient 기본값)
    expect(
      await screen.findByText('최신 날씨를 불러오지 못해 09:30에 받은 내용을 보여줘요', {}, { timeout: 5000 }),
    ).toBeTruthy();
  });
});

describe('동네 검색 (SCR-03)', () => {
  async function openSearch(route: Route) {
    await completedOnboarding();
    const fake = server(route);
    await renderRouter(APP_DIR);
    await screen.findByText('동네를 골라 주세요');
    await testRouter.push('/neighborhood-search');
    return fake;
  }

  it('결과가 없으면 읍면동 검색 안내', async () => {
    await openSearch((_call, url) => (url.pathname === '/api/v1/neighborhoods/search' ? jsonResponse(200, []) : undefined));

    fireEvent.changeText(screen.getByTestId('neighborhood-search-input'), '없는동');

    expect(await screen.findByText('찾는 동네가 없어요. 읍면동 이름으로 검색해 보세요')).toBeTruthy();
  });

  it('검색 실패 → 다시 시도로 결과를 받는다', async () => {
    let fail = true;
    const { calls } = await openSearch((_call, url) => {
      if (url.pathname !== '/api/v1/neighborhoods/search') {
        return undefined;
      }
      return fail ? problem(400, 'INVALID_REQUEST') : jsonResponse(200, [neighborhoodResponse('N7', '삼성1동')]);
    });

    fireEvent.changeText(screen.getByTestId('neighborhood-search-input'), '삼성');
    expect(await screen.findByText('검색하지 못했어요')).toBeTruthy();
    expect(new URL(calls.find((call) => pathOf(call) === '/api/v1/neighborhoods/search')!.url).searchParams.get('q')).toBe(
      '삼성',
    );

    fail = false;
    fireEvent.press(screen.getByText('다시 시도'));
    expect(await screen.findByText('서울특별시 강남구 삼성1동')).toBeTruthy();
  });

  it('결과를 고르면 "이 동네 보기"로 보는 동네를 바꾸고 닫는다', async () => {
    await openSearch((_call, url) => {
      if (url.pathname === '/api/v1/neighborhoods/search') {
        return jsonResponse(200, [neighborhoodResponse('N7', '삼성1동')]);
      }
      if (url.pathname === '/api/v1/neighborhoods') {
        return jsonResponse(200, []);
      }
      if (url.pathname === '/api/v1/home') {
        return jsonResponse(200, homeView({ neighborhood: { ...homeView().neighborhood, id: 'N7', eupmyeondongName: '삼성1동' } }));
      }
      return undefined;
    });

    fireEvent.changeText(screen.getByTestId('neighborhood-search-input'), '삼성');
    fireEvent.press(await screen.findByTestId('search-result-N7'));
    fireEvent.press(await screen.findByTestId('search-view'));

    await waitFor(() => expect(screen).toHavePathname('/'));
    expect(viewingStore.getState().viewingNeighborhoodId).toBe('N7');
    expect(await screen.findByText('둘러보는 동네')).toBeTruthy();
  });

  it('첫 실행에서 고르면 바로 보는 동네로 정하고 관심 동네 추가를 묻는다', async () => {
    server((_call, url) => {
      if (url.pathname === '/api/v1/neighborhoods/search') {
        return jsonResponse(200, [neighborhoodResponse('N7', '삼성1동')]);
      }
      if (url.pathname === '/api/v1/neighborhoods') {
        return jsonResponse(200, []);
      }
      if (url.pathname === '/api/v1/home') {
        return jsonResponse(200, homeView());
      }
      return undefined;
    });
    await renderRouter(APP_DIR);
    await waitFor(() => expect(screen).toHavePathname('/welcome'));
    fireEvent.press(screen.getByTestId('welcome-later'));
    await waitFor(() => expect(screen).toHavePathname('/neighborhood-search'));

    fireEvent.changeText(screen.getByTestId('neighborhood-search-input'), '삼성');
    fireEvent.press(await screen.findByTestId('search-result-N7'));

    expect(await screen.findByTestId('onboarding-add-prompt')).toBeTruthy();
    expect(viewingStore.getState().viewingNeighborhoodId).toBe('N7');
    fireEvent.press(screen.getByTestId('onboarding-add-interest'));

    await waitFor(() => expect(screen).toHavePathname('/'));
    await expect(generalStore.get('guestInterestNeighborhoods')).resolves.toEqual(['N7']);
  });
});

describe('동네 전환 시트 (SCR-02)', () => {
  it('관심 동네가 최대 개수면 추가를 막고 안내한다. 고르면 보는 동네가 바뀐다', async () => {
    await completedOnboarding('A');
    await generalStore.set('guestInterestNeighborhoods', ['A', 'B', 'C', 'D', 'E']);
    server((_call, url) => {
      if (url.pathname === '/api/v1/neighborhoods') {
        return jsonResponse(200, url.searchParams.getAll('ids').map((id) => neighborhoodResponse(id, `${id}동`)));
      }
      if (url.pathname === '/api/v1/home') {
        return jsonResponse(200, homeView());
      }
      return undefined;
    });
    await renderRouter(APP_DIR);
    await screen.findByText('역삼1동');
    fireEvent.press(screen.getByTestId('home-header'));
    await waitFor(() => expect(screen).toHavePathname('/neighborhood-switch'));

    expect(await screen.findByText('위치 사용 안 함')).toBeTruthy();
    expect(await screen.findByTestId('interest-full')).toBeTruthy();
    expect(screen.getByTestId('interest-add').props.accessibilityState).toMatchObject({ disabled: true });

    fireEvent.press(screen.getByTestId('interest-row-C'));
    await waitFor(() => expect(viewingStore.getState().viewingNeighborhoodId).toBe('C'));
    await expect(generalStore.get('lastViewedNeighborhood')).resolves.toEqual({ neighborhoodId: 'C' });
  });
});
