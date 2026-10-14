// SCR-04 체감 기록: 온도 필수, 비회원 가입 유도(선택 보존), 오래된 판정 재판정, 실패 시 선택 유지 + 같은 요청 식별자,
// 재동의 전 쓰기 차단, 복원된 입력 채우기
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { signupSheet } from '@/auth/signupSheet';
import { termsStatus } from '@/auth/termsStatus';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore, restoredActions } from '@/pending/pendingAction';
import { queryKeys } from '@/query/queryKeys';
import { sessionStore } from '@/session/sessionStore';
import { type FakeCall, jsonResponse, problem, tokens } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';

import { FeelingRecordScreen, RECORD_LOCATION_UNAVAILABLE } from '../FeelingRecordScreen';
import type { TagDefinitions } from '../types';

const mockedLocation = Location as jest.Mocked<typeof Location>;
const permission = (status: 'granted' | 'denied') =>
  ({ status, granted: status === 'granted', canAskAgain: false, expires: 'never' }) as never;

const TAGS: TagDefinitions = {
  temperature: [
    { name: 'COLD', level: 2, label: '추움' },
    { name: 'VERY_COLD', level: 1, label: '매우 추움' },
    { name: 'JUST_RIGHT', level: 4, label: '딱 좋음' },
  ],
  humidity: [{ name: 'DRY', level: 2, label: '건조해요' }],
  wind: [{ name: 'LIGHT', level: 2, label: '조금 불어요' }],
};

let queryClient: QueryClient;

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  restoredActions.reset();
  signupSheet.setMounted(false);
  viewingStore.reset();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(permission('granted'));
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(queryKeys.feeling.tags(), TAGS);
});

afterEach(() => queryClient.clear());

function renderScreen() {
  return render(
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider initialMetrics={{ frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 0, left: 0, right: 0, bottom: 0 } }}>
        <FeelingRecordScreen />
      </SafeAreaProvider>
    </QueryClientProvider>,
  );
}

async function asMember() {
  await sessionStore.saveTokens(tokens('m'));
}

const recordPosts = (calls: FakeCall[]) => calls.filter((call) => pathOf(call) === '/api/v1/feeling/records');

const saved = {
  recordId: 'rec-1',
  neighborhoodId: 'N1',
  createdAt: '2026-10-15T03:00:00Z',
  recordedAt: '2026-10-15T03:00:00Z',
  temperatureTag: 'COLD',
  humidityTag: 'DRY',
  windTag: null,
  temperatureSensitivity: 'NORMAL',
  source: 'DIRECT',
  snapshotSecured: true,
  overwritten: false,
  overwritableUntil: '2026-10-15T04:00:00Z',
};

it('서버 태그 정의를 단계 순서로 보여주고, 아무것도 미리 고르지 않으며 온도를 골라야 저장할 수 있다', () => {
  renderScreen();
  const labels = screen.getAllByRole('radio').map((node) => node.props.testID);
  expect(labels.slice(0, 3)).toEqual(['temperature-VERY_COLD', 'temperature-COLD', 'temperature-JUST_RIGHT']);
  expect(screen.getAllByRole('radio').every((node) => node.props.accessibilityState.selected === false)).toBe(true);
  expect(screen.getByTestId('record-save').props.accessibilityState.disabled).toBe(true);

  fireEvent.press(screen.getByTestId('humidity-DRY'));
  expect(screen.getByTestId('record-save').props.accessibilityState.disabled).toBe(true);

  fireEvent.press(screen.getByTestId('temperature-COLD'));
  expect(screen.getByTestId('record-save').props.accessibilityState.disabled).toBe(false);
});

it('비회원은 저장 때 가입 유도 — 고른 태그를 보류 행동으로 저장하고 서버로 보내지 않는다', async () => {
  const { calls } = installFetch(() => problem(500, 'X'));
  renderScreen();
  fireEvent.press(screen.getByTestId('temperature-COLD'));
  fireEvent.press(screen.getByTestId('wind-LIGHT'));
  fireEvent.press(screen.getByTestId('record-save'));

  await waitFor(async () =>
    expect(await pendingActionStore.load()).toEqual({
      kind: 'feelingRecord',
      route: '/feeling-record',
      neighborhoodId: null,
      payload: { temperature: 'COLD', humidity: null, wind: 'LIGHT' },
    }),
  );
  expect(recordPosts(calls)).toHaveLength(0);
  // 화면의 선택은 그대로
  expect(screen.getByTestId('temperature-COLD').props.accessibilityState.selected).toBe(true);
});

it('판정이 오래됐으면 저장 전에 재판정하고, 좌표·정확도·요청 식별자와 함께 현재 동네로 보낸다', async () => {
  await asMember();
  await viewingStore.applyJudgement('N1', '2026-01-01T00:00:00Z');
  const { calls } = installFetch((call) => {
    if (pathOf(call) === '/api/v1/members/me/current-neighborhood') {
      return jsonResponse(200, { neighborhood: neighborhoodResponse('N2', '역삼2동'), confirmedAt: null, refreshed: true });
    }
    if (pathOf(call) === '/api/v1/feeling/records') {
      return jsonResponse(200, saved);
    }
    return problem(500, 'X');
  });
  const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
  renderScreen();
  fireEvent.press(screen.getByTestId('temperature-COLD'));
  fireEvent.press(screen.getByTestId('record-save'));

  expect(await screen.findByTestId('record-saved')).toBeTruthy();
  // 동네 이름 조회(GET /neighborhoods/{id})는 표시용이라 빼고 본다
  const paths = calls.map(pathOf).filter((path) => !path.startsWith('/api/v1/neighborhoods/'));
  expect(paths).toEqual(['/api/v1/members/me/current-neighborhood', '/api/v1/feeling/records']);
  const post = recordPosts(calls)[0];
  expect(post.body).toEqual({ temperature: 'COLD', humidity: null, wind: null, latitude: 37.5, longitude: 127.0, accuracyMeters: 30 });
  expect(post.headers['Idempotency-Key']).toEqual(expect.any(String));
  expect(viewingStore.getState().current?.neighborhoodId).toBe('N2');
  expect(screen.getByText('글로도 남기기')).toBeTruthy();
  expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.home.all });
  expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.me.feelingRecords() });
});

it('저장 실패: 사유를 그대로 보여주고 선택을 유지하며, 다시 시도는 같은 요청 식별자 — 선택을 바꾸면 새 식별자', async () => {
  await asMember();
  await viewingStore.applyJudgement('N1', new Date().toISOString());
  let fail = true;
  const { calls } = installFetch((call) => {
    if (pathOf(call) !== '/api/v1/feeling/records') {
      return problem(500, 'X');
    }
    return fail ? problem(422, 'CURRENT_NEIGHBORHOOD_UNVERIFIED') : jsonResponse(200, saved);
  });
  renderScreen();
  fireEvent.press(screen.getByTestId('temperature-COLD'));
  fireEvent.press(screen.getByTestId('record-save'));

  expect(await screen.findByText('CURRENT_NEIGHBORHOOD_UNVERIFIED 안내')).toBeTruthy();
  expect(screen.getByTestId('temperature-COLD').props.accessibilityState.selected).toBe(true);

  fireEvent.press(screen.getByTestId('record-save'));
  await waitFor(() => expect(recordPosts(calls)).toHaveLength(2));
  const [first, second] = recordPosts(calls);
  expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);

  fireEvent.press(screen.getByTestId('temperature-JUST_RIGHT'));
  fail = false;
  await waitFor(() => expect(screen.getByTestId('record-save').props.accessibilityState.disabled).toBe(false));
  fireEvent.press(screen.getByTestId('record-save'));
  expect(await screen.findByTestId('record-saved')).toBeTruthy();
  expect(recordPosts(calls)[2].headers['Idempotency-Key']).not.toBe(first.headers['Idempotency-Key']);
});

it('현재 동네를 확인할 수 없으면 기록하지 않는다', async () => {
  await asMember();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(permission('denied'));
  const { calls } = installFetch(() => problem(500, 'X'));
  renderScreen();
  fireEvent.press(screen.getByTestId('temperature-COLD'));
  fireEvent.press(screen.getByTestId('record-save'));

  expect(await screen.findByText(RECORD_LOCATION_UNAVAILABLE)).toBeTruthy();
  expect(recordPosts(calls)).toHaveLength(0);
});

it('재동의 전에는 저장하지 않고 재동의 시트로(입력은 보류 행동으로 보존)', async () => {
  await asMember();
  termsStatus.markRequired();
  const { calls } = installFetch(() => problem(500, 'X'));
  renderScreen();
  fireEvent.press(screen.getByTestId('temperature-COLD'));
  fireEvent.press(screen.getByTestId('record-save'));

  await waitFor(async () => expect(await pendingActionStore.load()).toMatchObject({ kind: 'feelingRecord' }));
  expect(recordPosts(calls)).toHaveLength(0);
});

it('가입 후 복원된 선택을 채우고 자동 저장하지 않는다', async () => {
  const { calls } = installFetch(() => problem(500, 'X'));
  renderScreen();
  act(() => {
    restoredActions.put({
      action: {
        kind: 'feelingRecord',
        route: '/feeling-record',
        neighborhoodId: null,
        payload: { temperature: 'VERY_COLD', humidity: 'DRY', wind: null },
      },
      notice: '현재 동네에서만 남길 수 있어요',
      autoApplied: false,
    });
  });

  expect(screen.getByTestId('temperature-VERY_COLD').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('humidity-DRY').props.accessibilityState.selected).toBe(true);
  expect(screen.getByText('현재 동네에서만 남길 수 있어요')).toBeTruthy();
  expect(recordPosts(calls)).toHaveLength(0);
});
