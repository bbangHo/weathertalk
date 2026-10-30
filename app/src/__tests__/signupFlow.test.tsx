// 07 §3 가입 유도 → SCR-10 → 복귀 (실제 src/app 라우트, 전역 fetch·위치는 가짜)
// 홈 체감 피드백(비회원) → 가입 시트 → 개발 로그인 → 신규 가입/기존 회원/재동의/티켓 무효 → 입력 복원(자동 제출 없음)
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import { renderRouter, screen } from 'expo-router/testing-library';
import * as SecureStore from 'expo-secure-store';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { LoginResponse, TermsResponse } from '@/auth/api';
import { signupSheet } from '@/auth/signupSheet';
import { SignupScreen } from '@/auth/SignupScreen';
import { termsStatus } from '@/auth/termsStatus';
import { homeCache } from '@/home/homeCache';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore, restoredActions } from '@/pending/pendingAction';
import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';
import { type FakeCall, jsonResponse, problem } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { homeView, neighborhoodResponse, sensitivityFeelingsOk } from '@/test-utils/homeFixtures';

const APP_DIR = 'src/app';
jest.setTimeout(30000);

const mockedLocation = Location as jest.Mocked<typeof Location>;
const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' } as never;

const service: TermsResponse = {
  kind: 'SERVICE',
  version: 1,
  title: '서비스 이용약관',
  required: true,
  effectiveAt: '2026-10-01T00:00:00Z',
  contentUrl: null,
};

const issuedTokens = {
  accessToken: 'access-new',
  accessTokenExpiresAt: '2026-10-16T00:00:00Z',
  refreshToken: 'refresh-new',
  refreshTokenExpiresAt: '2026-11-16T00:00:00Z',
};

function registered(overrides: Partial<NonNullable<LoginResponse['member']>> = {}): LoginResponse {
  return {
    registered: true,
    tokens: issuedTokens,
    member: {
      memberId: 'm1',
      nickname: '맑은하늘12',
      temperatureSensitivity: 'NORMAL',
      termsReagreementRequired: false,
      termsToReagree: [],
      ...overrides,
    },
    signupTicket: null,
    terms: null,
  };
}

const profile = (reagree: boolean) => ({
  memberId: 'm1',
  nickname: '맑은하늘12',
  temperatureSensitivity: 'NORMAL',
  email: null,
  providers: ['DEV'],
  nicknameChangeAvailableAt: null,
  termsReagreementRequired: reagree,
  termsToReagree: reagree ? [service] : [],
});

const home = homeView({
  sensitivityFeelings: sensitivityFeelingsOk(),
  personal: { highlightedGroup: 'NORMAL', basisGroup: 'NORMAL', selectionSuggested: false },
});

type Route = (call: FakeCall, url: URL) => Response | undefined;

function server(route: Route) {
  return installFetch((call) => {
    const url = new URL(call.url);
    const custom = route(call, url);
    if (custom) {
      return custom;
    }
    if (url.pathname === '/api/v1/home') {
      return jsonResponse(200, home);
    }
    if (url.pathname === '/api/v1/members/me/current-neighborhood') {
      return jsonResponse(200, { neighborhood: neighborhoodResponse('N1', '역삼1동'), confirmedAt: null, refreshed: true });
    }
    return problem(503, 'TEMPORARILY_UNAVAILABLE');
  });
}

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  signupSheet.setMounted(false);
  restoredActions.reset();
  viewingStore.reset();
  homeCache.clearMemory();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
  // 현재 동네 N1을 방금 판정한 비회원 기기, 민감도는 '보통'
  await generalStore.set('onboardingCompleted', true);
  await generalStore.set('lastViewedNeighborhood', { neighborhoodId: 'N1' });
  await generalStore.set('currentNeighborhood', { neighborhoodId: 'N1', resolvedAt: new Date().toISOString() });
  await generalStore.set('guestTemperatureSensitivity', 'NORMAL');
});

async function openSignupFromFeedback() {
  await renderRouter(APP_DIR);
  fireEvent.press(await screen.findByTestId('feedback-COLDER'));
  await waitFor(() => expect(screen).toHavePathname('/signup'));
  await expect(pendingActionStore.load()).resolves.toMatchObject({
    kind: 'feedback',
    route: '/',
    neighborhoodId: 'N1',
    payload: { answer: 'COLDER', group: 'NORMAL', temperatureTag: 'JUST_RIGHT' },
  });
}

async function devLogin(id = 'tester1') {
  fireEvent.changeText(await screen.findByTestId('dev-login-id'), id);
  fireEvent.press(screen.getByTestId('dev-login-submit'));
}

const feedbackPosts = (calls: FakeCall[]) => calls.filter((call) => pathOf(call) === '/api/v1/feeling/feedbacks');

describe('가입 유도 (07 §3)', () => {
  it('비회원 피드백은 보류 행동을 저장하고 가입 시트를 연다. 취소하면 보류 행동만 지운다', async () => {
    const { calls } = server(() => undefined);
    await openSignupFromFeedback();
    // 제공자 버튼은 준비 중(A5), 개발 빌드에는 개발 로그인
    expect(screen.getByText('카카오로 계속하기 (준비 중)')).toBeTruthy();
    expect(screen.getByTestId('dev-login')).toBeTruthy();

    fireEvent.press(screen.getByTestId('signup-cancel'));

    await waitFor(() => expect(screen).toHavePathname('/'));
    await expect(pendingActionStore.load()).resolves.toBeNull();
    expect(feedbackPosts(calls)).toHaveLength(0);
  });

  it('신규 가입: 약관 → 민감도(기기 값 미리 선택) → 닉네임 안내 → 복귀, 피드백은 자동 제출하지 않는다', async () => {
    const { calls } = server((call, url) => {
      if (url.pathname === '/api/v1/dev/login') {
        return jsonResponse(200, {
          registered: false,
          tokens: null,
          member: null,
          signupTicket: { ticket: 'ticket-1', expiresAt: '2026-10-15T01:00:00Z' },
          terms: [service],
        });
      }
      if (url.pathname === '/api/v1/signup') {
        return jsonResponse(200, registered());
      }
      if (url.pathname === '/api/v1/members/me') {
        return jsonResponse(200, profile(false));
      }
      return undefined;
    });
    await openSignupFromFeedback();
    await devLogin();

    expect(await screen.findByTestId('signup-terms')).toBeTruthy();
    expect(screen.getByTestId('terms-submit').props.accessibilityState.disabled).toBe(true);
    fireEvent.press(screen.getByTestId('terms-SERVICE'));
    fireEvent.press(screen.getByTestId('terms-submit'));

    expect(await screen.findByTestId('signup-sensitivity')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('sensitivity-NORMAL').props.accessibilityState.selected).toBe(true));
    fireEvent.press(screen.getByTestId('signup-submit'));

    expect(await screen.findByText("닉네임은 '맑은하늘12'이에요. 마이페이지에서 바꿀 수 있어요.")).toBeTruthy();
    const signupCall = calls.find((call) => pathOf(call) === '/api/v1/signup')!;
    expect(signupCall.body).toEqual({
      signupTicket: 'ticket-1',
      agreements: [{ kind: 'SERVICE', version: 1, agreed: true }],
      temperatureSensitivity: 'NORMAL',
    });
    expect(calls.find((call) => pathOf(call) === '/api/v1/dev/login')!.body).toEqual({ providerUserId: 'tester1', email: null });
    expect(sessionStore.getState().status).toBe('member');
    // 로그인 직후: 기기 확인 → (관심 동네 없음) → 현재 동네 판정
    const paths = calls.map(pathOf);
    expect(paths).toContain('/api/v1/notification/devices/confirm');
    expect(paths).toContain('/api/v1/members/me/current-neighborhood');
    await expect(generalStore.get('guestTemperatureSensitivity')).resolves.toBeNull();

    fireEvent.press(screen.getByTestId('signup-ack'));

    await waitFor(() => expect(screen).toHavePathname('/'));
    expect(await screen.findByTestId('feedback-restored')).toBeTruthy();
    expect(screen.getByText(/가입 전에 '더 추워요'를 골랐어요/)).toBeTruthy();
    expect(feedbackPosts(calls)).toHaveLength(0);
    await expect(pendingActionStore.load()).resolves.toBeNull();
  });

  it('가입 티켓이 무효면 로그인 단계로 돌아가 사유를 보여준다', async () => {
    server((_call, url) => {
      if (url.pathname === '/api/v1/dev/login') {
        return jsonResponse(200, {
          registered: false,
          tokens: null,
          member: null,
          signupTicket: { ticket: 'old', expiresAt: '2026-10-15T01:00:00Z' },
          terms: [service],
        });
      }
      if (url.pathname === '/api/v1/signup') {
        return problem(401, 'SIGNUP_TICKET_INVALID');
      }
      return undefined;
    });
    await openSignupFromFeedback();
    await devLogin();
    fireEvent.press(await screen.findByTestId('terms-SERVICE'));
    fireEvent.press(screen.getByTestId('terms-submit'));
    fireEvent.press(await screen.findByTestId('signup-submit'));

    expect(await screen.findByText('SIGNUP_TICKET_INVALID 안내')).toBeTruthy();
    expect(screen.getByTestId('dev-login')).toBeTruthy();
    expect(sessionStore.getState().status).toBe('guest');
  });

  it('기존 회원은 바로 복귀하고, 재동의가 필요하면 바뀐 약관을 먼저 보여준다. "나중에"면 쓰기는 다시 시트를 연다', async () => {
    const { calls } = server((_call, url) => {
      if (url.pathname === '/api/v1/dev/login') {
        return jsonResponse(200, registered({ termsReagreementRequired: true, termsToReagree: [service] }));
      }
      if (url.pathname === '/api/v1/members/me') {
        return jsonResponse(200, profile(true));
      }
      return undefined;
    });
    await openSignupFromFeedback();
    await devLogin();

    expect(await screen.findByText('약관이 바뀌었어요')).toBeTruthy();
    expect(screen.getByText('[필수] 서비스 이용약관')).toBeTruthy();
    fireEvent.press(screen.getByTestId('terms-secondary'));

    await waitFor(() => expect(screen).toHavePathname('/'));
    expect(sessionStore.getState().status).toBe('member');
    expect(await screen.findByTestId('feedback-restored')).toBeTruthy();

    // 재동의 전 쓰기 → 재동의 시트, 서버로 보내지 않는다(§2 규칙 12)
    fireEvent.press(await screen.findByTestId('feedback-RIGHT'));
    await waitFor(() => expect(screen).toHavePathname('/signup'));
    expect(await screen.findByText('약관이 바뀌었어요')).toBeTruthy();
    expect(feedbackPosts(calls)).toHaveLength(0);
    await expect(pendingActionStore.load()).resolves.toMatchObject({ kind: 'feedback', payload: { answer: 'RIGHT' } });
  });
});

describe('개발 전용 로그인 노출 (07 §4 규칙 4)', () => {
  function renderSignup(devEntryEnabled: boolean) {
    const client = new QueryClient();
    return render(
      <QueryClientProvider client={client}>
        <SafeAreaProvider initialMetrics={{ frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 0, left: 0, right: 0, bottom: 0 } }}>
          <SignupScreen mode="signup" devEntryEnabled={devEntryEnabled} />
        </SafeAreaProvider>
      </QueryClientProvider>,
    );
  }

  it('DEV_ENTRY_ENABLED=false면 개발 로그인이 없다', () => {
    const view = renderSignup(false);
    expect(view.queryByTestId('dev-login')).toBeNull();
    expect(view.getByText('구글로 계속하기 (준비 중)')).toBeTruthy();
    view.unmount();
  });

  it('개발 빌드면 보인다', () => {
    const view = renderSignup(true);
    expect(view.getByTestId('dev-login')).toBeTruthy();
    view.unmount();
  });
});

describe('빌드 대상별 로그인 수단 (앱인토스: 토스 식별키)', () => {
  it('쓸 수 있는 로그인 버튼을 누르면 그 제공자로 로그인하고, 처음이면 약관 단계로 간다', async () => {
    const login = jest.fn(async () => ({
      registered: false,
      tokens: null,
      member: null,
      signupTicket: { ticket: 't1', expiresAt: '2026-10-16T00:00:00Z' },
      terms: [service],
    }));
    const client = new QueryClient();
    const view = render(
      <QueryClientProvider client={client}>
        <SafeAreaProvider initialMetrics={{ frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 0, left: 0, right: 0, bottom: 0 } }}>
          <SignupScreen
            mode="signup"
            devEntryEnabled={false}
            providerButtons={[{ key: 'toss', title: '토스로 계속하기', login }]}
          />
        </SafeAreaProvider>
      </QueryClientProvider>,
    );

    expect(view.queryByText('구글로 계속하기 (준비 중)')).toBeNull();
    fireEvent.press(view.getByTestId('provider-toss'));

    expect(await view.findByText('약관에 동의해 주세요')).toBeTruthy();
    expect(login).toHaveBeenCalledTimes(1);
    view.unmount();
  });
});
