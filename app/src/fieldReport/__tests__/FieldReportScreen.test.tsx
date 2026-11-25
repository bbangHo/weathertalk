// SCR-05 현장 제보: 축별 선택·제보와 요청 식별자 재사용, 비회원 가입 유도(선택 보존·자동 제출 없음), 오래된 판정 재판정,
// 판정 불가 차단, 하루 한도 사유, 취소 기한(서버 기한·기한 지남 거절·타이머)
import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';

import { signupSheet } from '@/auth/signupSheet';
import * as localNeighborhood from '@/location/localNeighborhood';
import { termsStatus } from '@/auth/termsStatus';
import { viewingStore } from '@/neighborhood/viewingStore';
import { pendingActionStore, restoredActions } from '@/pending/pendingAction';
import { queryKeys } from '@/query/queryKeys';
import { sessionStore } from '@/session/sessionStore';
import { type FakeCall, jsonResponse, problem, tokens } from '@/test-utils/apiClient';
import { axisStatus, fieldReportStatus, myReport } from '@/test-utils/fieldReportFixtures';
import { installFetch, pathOf } from '@/test-utils/globalFetch';
import { neighborhoodResponse } from '@/test-utils/homeFixtures';
import { renderWithClient, testQueryClient } from '@/test-utils/render';

import { FieldReportScreen } from '../FieldReportScreen';
import { CANCEL_WINDOW_PASSED_MESSAGE, REPORT_LOCATION_UNAVAILABLE } from '../labels';
import type { FieldReportResult, FieldReportStatus } from '../types';

const mockedLocation = Location as jest.Mocked<typeof Location>;
const permission = (status: 'granted' | 'denied') =>
  ({ status, granted: status === 'granted', canAskAgain: false, expires: 'never' }) as never;

const STATUS_PATH = '/api/v1/weather/neighborhoods/N1/field-reports';
const SUBMIT_PATH = '/api/v1/weather/field-reports';

const inMinutes = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();

beforeEach(async () => {
  await AsyncStorage.clear();
  (SecureStore as unknown as { __store: Map<string, string> }).__store.clear();
  sessionStore.becomeGuest();
  termsStatus.reset();
  restoredActions.reset();
  signupSheet.setMounted(false);
  viewingStore.reset();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(permission('granted'));
});

const submits = (calls: FakeCall[]) => calls.filter((call) => call.method === 'POST' && pathOf(call) === SUBMIT_PATH);

/** 현황·동네 이름 조회는 기본 응답, 나머지는 handler가 정한다 */
function serve(status: FieldReportStatus, handler: (call: FakeCall) => Response | null = () => null) {
  return installFetch((call) => {
    const custom = handler(call);
    if (custom) {
      return custom;
    }
    if (pathOf(call) === STATUS_PATH) {
      return jsonResponse(200, status);
    }
    if (pathOf(call) === '/api/v1/neighborhoods/N1') {
      return jsonResponse(200, neighborhoodResponse('N1', '역삼1동'));
    }
    return problem(500, 'X');
  });
}

function result(kind: 'RAIN' | 'CLEAR' | 'DUST_BAD', axis: 'SKY_PRECIPITATION' | 'AIR' = 'SKY_PRECIPITATION'): FieldReportResult {
  const report = myReport(axis, kind, inMinutes(10), `new-${kind}`);
  return {
    report,
    status: fieldReportStatus({ axes: [axisStatus('SKY_PRECIPITATION', [[kind, 1]]), axisStatus('AIR')], myReports: [report] }),
  };
}

async function asMember() {
  await sessionStore.saveTokens(tokens('m'));
}

it('현재 동네 이름, 축별 종류, 현황과 내 제보(선택 상태)를 서버 응답 그대로 보여준다', async () => {
  await asMember();
  await viewingStore.applyJudgement('N1', new Date().toISOString());
  serve(
    fieldReportStatus({
      axes: [axisStatus('SKY_PRECIPITATION', [['RAIN', 3], ['CLEAR', 1]]), axisStatus('AIR')],
      myReports: [myReport('SKY_PRECIPITATION', 'RAIN', inMinutes(5))],
    }),
  );
  renderWithClient(<FieldReportScreen />);

  expect(await screen.findByText('서울특별시 강남구 역삼1동')).toBeTruthy();
  expect(await screen.findByText('지금 4명 제보 · 비 3명, 맑음 1명')).toBeTruthy();
  const sky = within(screen.getByTestId('axis-SKY_PRECIPITATION'));
  expect(sky.getAllByRole('radio').map((node) => node.props.testID)).toEqual([
    'kind-RAIN',
    'kind-SNOW',
    'kind-OVERCAST',
    'kind-CLEAR',
  ]);
  expect(screen.getByTestId('kind-RAIN').props.accessibilityState.selected).toBe(true);
  expect(within(screen.getByTestId('axis-AIR')).getAllByRole('radio').map((node) => node.props.testID)).toEqual([
    'kind-DUST_BAD',
    'kind-AIR_CLEAN',
  ]);
  expect(screen.getByText('아직 제보가 없어요')).toBeTruthy();
  // 내 제보를 고른 상태만으로는 다시 보내지 않는다(종류를 골라야 제보 버튼이 켜진다)
  expect(screen.getByTestId('submit-SKY_PRECIPITATION').props.accessibilityState.disabled).toBe(true);
});

it('비회원은 제보 때 가입 유도 — 고른 축별 종류를 보류 행동으로 저장하고 서버로 보내지 않으며 선택을 유지한다', async () => {
  await viewingStore.applyJudgement('N1', new Date().toISOString());
  const { calls } = serve(fieldReportStatus());
  renderWithClient(<FieldReportScreen />);
  fireEvent.press(screen.getByTestId('kind-RAIN'));
  fireEvent.press(screen.getByTestId('kind-DUST_BAD'));
  fireEvent.press(screen.getByTestId('submit-SKY_PRECIPITATION'));

  await waitFor(async () =>
    expect(await pendingActionStore.load()).toEqual({
      kind: 'fieldReport',
      route: '/field-report',
      neighborhoodId: null,
      payload: { selections: { SKY_PRECIPITATION: 'RAIN', AIR: 'DUST_BAD' } },
    }),
  );
  expect(submits(calls)).toHaveLength(0);
  expect(screen.getByTestId('kind-RAIN').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('kind-DUST_BAD').props.accessibilityState.selected).toBe(true);
});

it('가입 후 복원: 선택을 채우고 안내를 보여주며 자동 제출하지 않는다(모르는 값은 버린다)', async () => {
  await asMember();
  await viewingStore.applyJudgement('N1', new Date().toISOString());
  const { calls } = serve(fieldReportStatus());
  renderWithClient(<FieldReportScreen />);
  act(() => {
    restoredActions.put({
      action: {
        kind: 'fieldReport',
        route: '/field-report',
        neighborhoodId: null,
        payload: { selections: { SKY_PRECIPITATION: 'SNOW', AIR: 'RAIN', FOG: 'FOG' } },
      },
      notice: '현재 동네에서만 남길 수 있어요',
      autoApplied: false,
    });
  });

  expect(screen.getByTestId('kind-SNOW').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('kind-DUST_BAD').props.accessibilityState.selected).toBe(false);
  expect(screen.getByTestId('report-notice')).toHaveTextContent('현재 동네에서만 남길 수 있어요');
  await waitFor(() => expect(calls.some((call) => pathOf(call) === STATUS_PATH)).toBe(true));
  expect(submits(calls)).toHaveLength(0);
});

it('판정이 오래됐으면 제보 전에 기기 안에서 재판정하고, 좌표 대신 그 동네 ID·요청 식별자로 보낸 뒤 현황을 갱신하고 홈을 무효화한다', async () => {
  await asMember();
  await viewingStore.applyJudgement('N1', '2026-01-01T00:00:00Z');
  // 기기 안 재판정 결과를 이 테스트의 가상 동네 N1로 둔다
  const judge = jest.spyOn(localNeighborhood, 'locateLocally').mockReturnValue({
    id: 'N1',
    sidoName: '서울특별시',
    sigunguName: '강남구',
    eupmyeondongName: '역삼1동',
    fullName: '서울특별시 강남구 역삼1동',
    shortName: '강남구 역삼1동',
  });
  const { calls } = serve(fieldReportStatus(), (call) =>
    pathOf(call) === SUBMIT_PATH ? jsonResponse(200, result('RAIN')) : null,
  );
  const client = testQueryClient();
  const invalidate = jest.spyOn(client, 'invalidateQueries');
  renderWithClient(<FieldReportScreen />, client);
  fireEvent.press(screen.getByTestId('kind-RAIN'));
  fireEvent.press(screen.getByTestId('submit-SKY_PRECIPITATION'));

  expect(await screen.findByTestId('report-done-SKY_PRECIPITATION')).toHaveTextContent('제보했어요');
  const writes = calls.filter((call) => call.method !== 'GET').map(pathOf);
  expect(writes).toEqual([SUBMIT_PATH]);
  expect(judge).toHaveBeenCalledWith(37.5, 127.0);
  judge.mockRestore();
  const post = submits(calls)[0];
  expect(post.body).toEqual({ kind: 'RAIN', neighborhoodId: 'N1' });
  expect(post.headers['Idempotency-Key']).toEqual(expect.any(String));
  expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.home.all });
  // 응답의 현황으로 바로 바뀌고 방금 제보는 취소할 수 있다
  expect(await screen.findByText('지금 1명 제보 · 비 1명')).toBeTruthy();
  expect(screen.getByTestId('kind-RAIN').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('cancel-SKY_PRECIPITATION')).toBeTruthy();
});

it('하루 한도 초과: 서버 사유를 보여주고 선택을 유지하며, 다시 시도는 같은 요청 식별자 — 종류를 바꾸면 새 식별자, 성공 뒤에도 새 식별자', async () => {
  await asMember();
  await viewingStore.applyJudgement('N1', new Date().toISOString());
  let fail = true;
  const { calls } = serve(fieldReportStatus(), (call) => {
    if (pathOf(call) !== SUBMIT_PATH) {
      return null;
    }
    return fail
      ? jsonResponse(429, { status: 429, code: 'FIELD_REPORT_DAILY_LIMIT_EXCEEDED', detail: '오늘은 더 제보할 수 없습니다.', dailyLimit: 20 })
      : jsonResponse(200, result((call.body as { kind: 'RAIN' | 'CLEAR' }).kind));
  });
  renderWithClient(<FieldReportScreen />);
  fireEvent.press(screen.getByTestId('kind-RAIN'));
  fireEvent.press(screen.getByTestId('submit-SKY_PRECIPITATION'));

  expect(await screen.findByTestId('report-error-SKY_PRECIPITATION')).toHaveTextContent('오늘은 더 제보할 수 없습니다.');
  expect(screen.getByTestId('kind-RAIN').props.accessibilityState.selected).toBe(true);

  fireEvent.press(screen.getByTestId('submit-SKY_PRECIPITATION'));
  await waitFor(() => expect(submits(calls)).toHaveLength(2));
  const [first, second] = submits(calls);
  expect(second.headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);

  await waitFor(() => expect(screen.getByTestId('submit-SKY_PRECIPITATION').props.accessibilityState.disabled).toBe(false));
  fireEvent.press(screen.getByTestId('kind-CLEAR'));
  fail = false;
  fireEvent.press(screen.getByTestId('submit-SKY_PRECIPITATION'));
  expect(await screen.findByTestId('report-done-SKY_PRECIPITATION')).toBeTruthy();
  const third = submits(calls)[2];
  expect(third.headers['Idempotency-Key']).not.toBe(first.headers['Idempotency-Key']);

  // 성공한 뒤 같은 종류를 다시 보내면 새 행동이다
  fireEvent.press(screen.getByTestId('kind-CLEAR'));
  await waitFor(() => expect(screen.getByTestId('submit-SKY_PRECIPITATION').props.accessibilityState.disabled).toBe(false));
  fireEvent.press(screen.getByTestId('submit-SKY_PRECIPITATION'));
  await waitFor(() => expect(submits(calls)).toHaveLength(4));
  expect(submits(calls)[3].headers['Idempotency-Key']).not.toBe(third.headers['Idempotency-Key']);
});

it('현재 동네를 확인할 수 없으면 제보하지 않고 막는다', async () => {
  await asMember();
  mockedLocation.getForegroundPermissionsAsync.mockResolvedValue(permission('denied'));
  const { calls } = serve(fieldReportStatus());
  renderWithClient(<FieldReportScreen />);
  expect(screen.getByTestId('report-neighborhood')).toHaveTextContent('제보할 때 현재 동네를 확인해요');
  fireEvent.press(screen.getByTestId('kind-DUST_BAD'));
  fireEvent.press(screen.getByTestId('submit-AIR'));

  expect(await screen.findByText(REPORT_LOCATION_UNAVAILABLE)).toBeTruthy();
  expect(submits(calls)).toHaveLength(0);
  expect(screen.getByTestId('kind-DUST_BAD').props.accessibilityState.selected).toBe(true);
});

describe('제보 취소', () => {
  it('서버가 준 기한 안의 내 제보만 취소 버튼을 보여주고, 취소하면 현황을 갱신한다', async () => {
    await asMember();
    await viewingStore.applyJudgement('N1', new Date().toISOString());
    const canceled = fieldReportStatus();
    const { calls } = serve(
      fieldReportStatus({
        myReports: [
          myReport('SKY_PRECIPITATION', 'RAIN', inMinutes(5), 'r-sky'),
          myReport('AIR', 'DUST_BAD', inMinutes(-1), 'r-air'),
        ],
      }),
      (call) =>
        pathOf(call) === `${SUBMIT_PATH}/r-sky/cancel`
          ? jsonResponse(200, { report: { ...myReport('SKY_PRECIPITATION', 'RAIN', inMinutes(5), 'r-sky'), canceledAt: new Date().toISOString() }, status: canceled })
          : null,
    );
    renderWithClient(<FieldReportScreen />);

    expect(await screen.findByTestId('cancel-SKY_PRECIPITATION')).toBeTruthy();
    expect(screen.queryByTestId('cancel-AIR')).toBeNull();
    expect(screen.getByTestId('my-report-AIR')).toHaveTextContent('내 제보: 미세먼지 나쁨 (12:00)');

    fireEvent.press(screen.getByTestId('cancel-SKY_PRECIPITATION'));
    expect(await screen.findByTestId('report-done-SKY_PRECIPITATION')).toHaveTextContent('제보를 취소했어요');
    expect(calls.filter((call) => pathOf(call) === `${SUBMIT_PATH}/r-sky/cancel`)).toHaveLength(1);
    expect(screen.queryByTestId('cancel-SKY_PRECIPITATION')).toBeNull();
    expect(screen.getByTestId('kind-RAIN').props.accessibilityState.selected).toBe(false);
  });

  it('취소 기한 지남으로 거절되면 안내를 보여주고 버튼을 숨긴다', async () => {
    await asMember();
    await viewingStore.applyJudgement('N1', new Date().toISOString());
    serve(fieldReportStatus({ myReports: [myReport('SKY_PRECIPITATION', 'RAIN', inMinutes(5), 'r-sky')] }), (call) =>
      pathOf(call) === `${SUBMIT_PATH}/r-sky/cancel` ? problem(422, 'FIELD_REPORT_CANCEL_WINDOW_PASSED') : null,
    );
    renderWithClient(<FieldReportScreen />);

    fireEvent.press(await screen.findByTestId('cancel-SKY_PRECIPITATION'));
    expect(await screen.findByText(CANCEL_WINDOW_PASSED_MESSAGE)).toBeTruthy();
    expect(screen.queryByTestId('cancel-SKY_PRECIPITATION')).toBeNull();
  });

  it('화면을 보는 중에 기한이 지나면 버튼이 저절로 사라진다', async () => {
    await asMember();
    await viewingStore.applyJudgement('N1', new Date().toISOString());
    serve(
      fieldReportStatus({
        myReports: [myReport('SKY_PRECIPITATION', 'RAIN', new Date(Date.now() + 1500).toISOString(), 'r-sky')],
      }),
    );
    renderWithClient(<FieldReportScreen />);

    expect(await screen.findByTestId('cancel-SKY_PRECIPITATION')).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId('cancel-SKY_PRECIPITATION')).toBeNull(), { timeout: 4000 });
  });
});
