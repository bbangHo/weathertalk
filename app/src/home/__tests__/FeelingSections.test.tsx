// SCR-01 4·5·8·9번 + 보정 제안 카드: 내 그룹 강조/선택 칩, 피드백 버튼 규칙, 옷 추천 기준, 어제 비교, 섹션 실패
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { termsStatus } from '@/auth/termsStatus';
import type { CalibrationOffer } from '@/feeling/types';
import type { ViewingKind } from '@/neighborhood/viewingStore';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore } from '@/pending/pendingAction';
import { queryKeys } from '@/query/queryKeys';
import { sessionStore } from '@/session/sessionStore';
import { generalStore } from '@/storage';
import { jsonResponse, problem, tokens } from '@/test-utils/apiClient';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { feeling, homeView, ok, sensitivityFeelingsOk } from '@/test-utils/homeFixtures';

import { HomeContent } from '../components/HomeContent';
import type { HomeView } from '../types';

const mockedLocation = Location as jest.Mocked<typeof Location>;
const granted = { status: 'granted', granted: true, canAskAgain: true, expires: 'never' } as never;

let queryClient: QueryClient;

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  viewingStore.reset();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(granted);
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 태그 정의는 받아 둔 상태로 시작한다(요청 기록을 쓰기 요청만으로 비교하려고)
  queryClient.setQueryData(queryKeys.feeling.tags(), { temperature: [], humidity: [], wind: [] });
});

afterEach(() => {
  queryClient.clear();
});

function renderHome(home: HomeView, options: { viewingKind?: ViewingKind; isMember?: boolean } = {}) {
  const onRefresh = jest.fn();
  render(
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider initialMetrics={{ frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 0, left: 0, right: 0, bottom: 0 } }}>
        <HomeContent
          home={home}
          viewingKind={options.viewingKind ?? 'current'}
          cachedSavedAt={null}
          refreshFailed={false}
          refreshing={false}
          onRefresh={onRefresh}
          onOpenSwitch={jest.fn()}
          isMember={options.isMember ?? false}
        />
      </SafeAreaProvider>
    </QueryClientProvider>,
  );
  return { onRefresh };
}

const withFeelings = (overrides: Partial<HomeView> = {}) =>
  homeView({
    sensitivityFeelings: sensitivityFeelingsOk(),
    personal: { highlightedGroup: 'NORMAL', basisGroup: 'NORMAL', selectionSuggested: false },
    ...overrides,
  });

/** 체질별 결과는 '지금 내 느낌은?' 카드 안에 접혀 있어 펼친 뒤 확인한다(A′). */
function openGroups() {
  fireEvent.press(screen.getByTestId('feelings-groups-toggle'));
}

describe('민감도별 체감 3그룹 (SCR-01 4번)', () => {
  it('체질별 결과는 처음엔 접혀 있고 누르면 펼쳐진다', () => {
    renderHome(withFeelings());
    expect(screen.queryByTestId('group-NORMAL')).toBeNull();
    openGroups();
    expect(screen.getByTestId('group-NORMAL')).toBeTruthy();
  });

  it('세 그룹의 체감 태그와 산출 출처 문구를 보여주고 내 그룹을 강조한다', () => {
    renderHome(withFeelings());
    openGroups();
    const heat = within(screen.getByTestId('group-HEAT_SENSITIVE'));
    expect(heat.getByText('조금 더움')).toBeTruthy();
    expect(heat.getByText('이 동네 주민 기록')).toBeTruthy();
    expect(within(screen.getByTestId('group-NORMAL')).getByText('비슷한 날씨의 기록')).toBeTruthy();
    expect(within(screen.getByTestId('group-COLD_SENSITIVE')).getByText('기본 기준')).toBeTruthy();

    expect(screen.getByTestId('group-NORMAL').props.accessibilityState.selected).toBe(true);
    expect(screen.getByTestId('group-HEAT_SENSITIVE').props.accessibilityState.selected).toBe(false);
    expect(screen.getByText('나 · 보통')).toBeTruthy();
    expect(screen.queryByTestId('sensitivity-chips')).toBeNull();
  });

  it('민감도를 고르지 않은 비회원은 강조 없이 "나는 어느 쪽?" 칩 — 고르면 기기에 저장하고 홈을 다시 부른다', async () => {
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    renderHome(withFeelings({ personal: { highlightedGroup: null, basisGroup: 'NORMAL', selectionSuggested: true } }));
    openGroups();

    for (const group of ['HEAT_SENSITIVE', 'NORMAL', 'COLD_SENSITIVE']) {
      expect(screen.getByTestId(`group-${group}`).props.accessibilityState.selected).toBe(false);
    }
    expect(screen.getByText('나는 어느 쪽?')).toBeTruthy();
    expect(screen.queryByTestId('feedback-area')).toBeNull();

    fireEvent.press(screen.getByTestId('pick-COLD_SENSITIVE'));

    await waitFor(async () => expect(await generalStore.get('guestTemperatureSensitivity')).toBe('COLD_SENSITIVE'));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.home.all }));
  });

  it('데이터 없음은 실패와 같이 보여준다(기준표로라도 값이 있어야 함)', () => {
    renderHome(homeView({ sensitivityFeelings: { status: 'EMPTY', data: null, failure: null } }));
    const section = within(screen.getByTestId('section-sensitivity-feelings'));
    expect(section.getByText('불러오지 못했어요')).toBeTruthy();
    expect(section.getByText('다시 시도')).toBeTruthy();
  });

  it('산출 불가 그룹은 "정보 없음"', () => {
    renderHome(
      withFeelings({
        sensitivityFeelings: ok({
          neighborhoodId: { value: 'N1' },
          basisTime: null,
          groups: [feeling('NORMAL', 'JUST_RIGHT')],
        }),
      }),
    );
    openGroups();
    expect(within(screen.getByTestId('group-HEAT_SENSITIVE')).getByText('정보 없음')).toBeTruthy();
  });
});

describe('체감 피드백 버튼 (SCR-01 5번)', () => {
  it('보는 동네가 현재 동네가 아니면 비활성 + 안내', () => {
    renderHome(withFeelings(), { viewingKind: 'interest' });
    expect(screen.getByTestId('feedback-RIGHT').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText('현재 동네에서만 답할 수 있어요')).toBeTruthy();
  });

  it('회원: 현재 동네가 아니라고 home이 알려주면 비활성', () => {
    renderHome(withFeelings({ feedbackStatus: ok({ available: false, recentRecord: null }) }), { isMember: true });
    expect(screen.getByTestId('feedback-COLDER').props.accessibilityState.disabled).toBe(true);
  });

  it('비회원이 누르면 가입 유도: 누른 답과 보여준 그룹·태그를 보류 행동으로 저장한다', async () => {
    const { calls } = installFetch(() => problem(500, 'X'));
    renderHome(withFeelings());
    fireEvent.press(screen.getByTestId('feedback-HOTTER'));
    await waitFor(async () =>
      expect(await pendingActionStore.load()).toEqual({
        kind: 'feedback',
        route: '/',
        neighborhoodId: 'N1',
        payload: { answer: 'HOTTER', group: 'NORMAL', temperatureTag: 'JUST_RIGHT' },
      }),
    );
    expect(calls).toHaveLength(0);
  });

  it('회원: 보여준 값·현재 동네 ID·요청 식별자로 저장하고 "기록했어요", 이전 기록이 있으면 덮어쓰기 안내', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await viewingStore.applyJudgement('N1', new Date().toISOString());
    const { calls } = installFetch((call) =>
      pathOf(call) === '/api/v1/feeling/feedbacks' ? jsonResponse(200, { recordId: 'r1', overwritten: true }) : problem(500, 'X'),
    );
    renderHome(
      withFeelings({
        feedbackStatus: ok({
          available: true,
          recentRecord: {
            id: { value: 'r0' },
            source: 'DIRECT',
            temperatureTag: 'COLD',
            recordedAt: '2026-10-15T03:00:00Z',
            createdAt: '2026-10-15T03:00:00Z',
            overwritableUntil: '2026-10-15T04:00:00Z',
          },
        }),
      }),
      { isMember: true },
    );
    expect(screen.getByText('다시 답하면 이전 기록을 바꿔요')).toBeTruthy();

    fireEvent.press(screen.getByTestId('feedback-COLDER'));

    expect(await screen.findByTestId('feedback-saved')).toBeTruthy();
    const post = calls.find((call) => pathOf(call) === '/api/v1/feeling/feedbacks')!;
    expect(post.headers['Idempotency-Key']).toEqual(expect.any(String));
    expect(post.body).toEqual({
      answer: 'COLDER',
      shown: {
        temperatureTag: 'JUST_RIGHT',
        source: 'FEELING_MAPPING',
        mappingVersion: 3,
        temperatureSensitivity: 'NORMAL',
        neighborhoodId: 'N1',
        basisTime: '2026-10-15T03:00:00Z',
        derivedAt: '2026-10-15T03:10:00Z',
      },
      neighborhoodId: 'N1',
    });
  });

  it('회원: 피드백 대상이 아니면 서버 사유를 그대로 보여주고 홈을 다시 부른다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await viewingStore.applyJudgement('N1', new Date().toISOString());
    installFetch(() => problem(422, 'FEEDBACK_TARGET_INVALID', { reason: 'STALE' }));
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    renderHome(withFeelings({ feedbackStatus: ok({ available: true, recentRecord: null }) }), { isMember: true });

    fireEvent.press(screen.getByTestId('feedback-RIGHT'));

    expect(await screen.findByText('FEEDBACK_TARGET_INVALID 안내')).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.home.all });
  });

  it('회원: 서버가 현재 동네를 확정하지 못하면 그 사유를 보여준다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    await viewingStore.applyJudgement('N1', new Date().toISOString());
    installFetch(() => problem(422, 'CURRENT_NEIGHBORHOOD_UNVERIFIED', { reason: 'ACCURACY_TOO_LOW' }));
    renderHome(withFeelings({ feedbackStatus: ok({ available: true, recentRecord: null }) }), { isMember: true });

    fireEvent.press(screen.getByTestId('feedback-RIGHT'));

    expect(await screen.findByText('CURRENT_NEIGHBORHOOD_UNVERIFIED 안내')).toBeTruthy();
  });
});

describe('옷 추천·외출 시각·어제 비교 (SCR-01 8·9번)', () => {
  const outfit = ok({
    temperatureLevel: 4,
    precipitation: 'NONE' as const,
    outfit: { outer: ['가디건'], top: ['긴팔'], bottom: ['청바지'], accessories: [], guide: '얇게 겹쳐 입어요' },
    tableVersion: 'v1',
  });

  it('민감도를 고르지 않은 비회원은 "보통 기준" + "내 민감도 고르기"', () => {
    renderHome(
      homeView({
        sensitivityFeelings: sensitivityFeelingsOk(),
        personal: { highlightedGroup: null, basisGroup: 'NORMAL', selectionSuggested: true },
        outfit,
      }),
    );
    const section = within(screen.getByTestId('section-outfit'));
    expect(section.getByText('보통 기준')).toBeTruthy();
    expect(section.getByText('내 민감도 고르기')).toBeTruthy();
    expect(section.getByText('겉옷 가디건 · 상의 긴팔 · 하의 청바지 · 소품 없음')).toBeTruthy();
    expect(section.getByText('얇게 겹쳐 입어요')).toBeTruthy();
  });

  it('외출 시각이 있으면 "HH:MM 외출 기준" 줄과 겹쳐 입기 안내', () => {
    renderHome(
      withFeelings({
        personal: { highlightedGroup: 'COLD_SENSITIVE', basisGroup: 'COLD_SENSITIVE', selectionSuggested: false },
        outfit,
        departureOutfit: ok({
          departures: [{ departureAt: '2026-10-15T23:00:00Z', feeling: feeling('COLD_SENSITIVE', 'COLD') }],
          recommendation: {
            recommendation: outfit.data!,
            layeringAdvised: true,
            layeringGuide: '아침저녁으로 쌀쌀해요. 겹쳐 입으세요',
            basisDepartureAt: '2026-10-15T23:00:00Z',
            basisTemperatureLevel: 2,
          },
        }),
      }),
    );
    expect(screen.getByText('추위를 많이 탐 기준')).toBeTruthy();
    expect(screen.getByText('08:00 외출 기준 · 추움')).toBeTruthy();
    expect(screen.getByText('아침저녁으로 쌀쌀해요. 겹쳐 입으세요')).toBeTruthy();
  });

  it('선행 섹션 실패(PREREQUISITE_FAILED)는 실패로 보여준다', () => {
    renderHome(
      homeView({
        sensitivityFeelings: { status: 'FAILED', data: null, failure: 'TIMEOUT' },
        outfit: { status: 'FAILED', data: null, failure: 'PREREQUISITE_FAILED' },
        yesterdayComparison: { status: 'FAILED', data: null, failure: 'PREREQUISITE_FAILED' },
      }),
    );
    expect(within(screen.getByTestId('section-outfit')).getByText('체감 정보를 불러오지 못해 보여줄 수 없어요')).toBeTruthy();
    expect(within(screen.getByTestId('section-yesterday')).getByText('체감 정보를 불러오지 못해 보여줄 수 없어요')).toBeTruthy();
  });

  it('어제와 비교: 비교 가능하면 문구, 아니면 데이터 없음', () => {
    renderHome(
      withFeelings({
        yesterdayComparison: ok({
          neighborhoodId: { value: 'N1' },
          temperatureSensitivity: 'NORMAL',
          comparable: true,
          levelDifference: -1,
          phrase: '어제보다 조금 쌀쌀해요',
          today: null,
          yesterday: null,
        }),
      }),
    );
    expect(within(screen.getByTestId('section-yesterday')).getByText('어제보다 조금 쌀쌀해요')).toBeTruthy();
  });
});

describe('민감도 보정 제안 카드 (회원)', () => {
  const offer: CalibrationOffer = {
    offerId: 'o1',
    currentGroup: 'NORMAL',
    suggestedGroup: 'COLD_SENSITIVE',
    recordCount: 12,
    weightedRecordCount: 10.5,
    errors: {},
    judgedAt: '2026-10-15T00:00:00Z',
    expiresAt: '2026-10-22T00:00:00Z',
    status: 'OFFERED',
  };

  it('수락하면 feeling 수락 API를 부르고 홈·프로필을 다시 부른다', async () => {
    await sessionStore.saveTokens(tokens('m'));
    const { calls } = installFetch(() => jsonResponse(200, { ...offer, status: 'ACCEPTED' }));
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    renderHome(withFeelings({ calibrationOffer: ok(offer) }), { isMember: true });

    expect(screen.getByText("최근 기록을 보면 '추위를 많이 탐' 그룹에 더 가까워요")).toBeTruthy();
    fireEvent.press(screen.getByTestId('calibration-accept'));

    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.me.profile() }));
    expect(calls.map(pathOf)).toEqual(['/api/v1/members/me/calibration-offers/o1/accept']);
  });

  it('직접 고르기는 온도 민감도 변경(DIRECT)', async () => {
    await sessionStore.saveTokens(tokens('m'));
    const { calls } = installFetch(() => jsonResponse(200, {}));
    renderHome(withFeelings({ calibrationOffer: ok(offer) }), { isMember: true });

    fireEvent.press(screen.getByText('직접 고르기'));
    fireEvent.press(screen.getByTestId('sensitivity-change-HEAT_SENSITIVE'));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(pathOf(calls[0])).toBe('/api/v1/members/me/temperature-sensitivity');
    expect(calls[0].method).toBe('PUT');
    expect(calls[0].body).toEqual({ temperatureSensitivity: 'HEAT_SENSITIVE', source: 'DIRECT' });
  });

  it('비회원·제안 없음이면 카드가 없다', () => {
    renderHome(withFeelings({ calibrationOffer: ok(offer) }), { isMember: false });
    expect(screen.queryByTestId('calibration-offer')).toBeNull();
  });
});
