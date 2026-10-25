// SCR-01 5번 차이 배지(서버가 표시하라고 한 축만, 받은 값 그대로) · 8번 현장 제보 현황(상태별 표시, 제보 버튼 규칙)
// · §7.3 focus 강조(현장 제보 "나도 제보하기", 시간별 예보 강수 시작 칸)
import { fireEvent, screen, within } from '@testing-library/react-native';
import { ScrollView } from 'react-native';

import { mockRouter, resetRouterMock } from '@/test-utils/routerMock';
import { axisDivergence, axisStatus, divergence, fieldReportStatus, myReport } from '@/test-utils/fieldReportFixtures';
import { homeView, ok, slot } from '@/test-utils/homeFixtures';
import { renderWithClient } from '@/test-utils/render';

import { HomeContent, type HomeContentProps } from '../components/HomeContent';
import { FOCUS_TOP_MARGIN } from '../focus';
import type { HomeView } from '../types';

// eslint-disable-next-line @typescript-eslint/no-require-imports
jest.mock('expo-router', () => require('@/test-utils/routerMock').expoRouterMock());

beforeEach(() => resetRouterMock());

function renderHome(home: HomeView, props: Partial<HomeContentProps> = {}) {
  const onRefresh = jest.fn();
  renderWithClient(
    <HomeContent
      home={home}
      viewingKind="current"
      cachedSavedAt={null}
      refreshFailed={false}
      refreshing={false}
      onRefresh={onRefresh}
      onOpenSwitch={jest.fn()}
      {...props}
    />,
  );
  return { onRefresh };
}

describe('예보 vs 주민 차이 배지', () => {
  it('badge=true인 축만, 서버가 준 기준 값·출처·근거 시각·주민 다수 의견 그대로 보여준다', () => {
    renderHome(
      homeView({
        divergence: ok(
          divergence([
            axisDivergence({
              axis: 'PRECIPITATION',
              badge: true,
              basisValue: 'NONE',
              basisLabel: '강수 없음',
              basisSource: 'FORECAST',
              basisTime: '2026-10-14T23:00:00Z',
              residentKind: 'RAIN',
              reporterCount: 5,
              majorityCount: 4,
            }),
            axisDivergence({ axis: 'SKY', badge: false, basisLabel: '흐림', basisSource: 'FORECAST', residentKind: 'CLEAR' }),
            axisDivergence({
              axis: 'AIR',
              badge: true,
              basisValue: 'GOOD',
              basisLabel: '좋음',
              basisSource: 'MEASUREMENT',
              basisTime: '2026-10-15T02:00:00Z',
              residentKind: 'DUST_BAD',
              reporterCount: 3,
              majorityCount: 3,
            }),
          ]),
        ),
      }),
    );
    const precipitation = within(screen.getByTestId('divergence-PRECIPITATION'));
    expect(precipitation.getByText('예보는 강수 없음, 주민 제보는 비')).toBeTruthy();
    expect(precipitation.getByText('08:00 예보 기준 · 주민 5명 중 4명')).toBeTruthy();
    const air = within(screen.getByTestId('divergence-AIR'));
    expect(air.getByText('측정값은 좋음, 주민 제보는 미세먼지 나쁨')).toBeTruthy();
    expect(air.getByText('11:00 측정 기준 · 주민 3명 중 3명')).toBeTruthy();
    expect(screen.queryByTestId('divergence-SKY')).toBeNull();
  });

  it('배지가 없으면(EMPTY·모두 false) 아무것도 그리지 않는다', () => {
    renderHome(homeView({ divergence: { status: 'EMPTY', data: divergence([axisDivergence()]), failure: null } }));
    expect(screen.queryByTestId('divergence-badges')).toBeNull();
    expect(screen.queryByTestId('divergence-failed')).toBeNull();
  });

  it('조회 실패(시간 초과·오류)는 다시 시도를 보여준다', () => {
    const { onRefresh } = renderHome(homeView({ divergence: { status: 'FAILED', data: null, failure: 'TIMEOUT' } }));
    const failed = within(screen.getByTestId('divergence-failed'));
    expect(failed.getByText('예보와 주민 제보 비교를 불러오지 못했어요')).toBeTruthy();
    fireEvent.press(failed.getByText('다시 시도'));
    expect(onRefresh).toHaveBeenCalled();
  });

  it('PREREQUISITE_FAILED면 배지 자리를 비운다', () => {
    renderHome(homeView({ divergence: { status: 'FAILED', data: null, failure: 'PREREQUISITE_FAILED' } }));
    expect(screen.queryByTestId('divergence-failed')).toBeNull();
  });
});

describe('현장 제보 현황', () => {
  it('축별 제보자 수·종류별 수·많은 의견·최근 제보 시각·내 제보를 보여주고, 버튼은 현장 제보 화면을 연다', () => {
    renderHome(
      homeView({
        fieldReports: ok(
          fieldReportStatus({
            axes: [axisStatus('SKY_PRECIPITATION', [['RAIN', 3], ['OVERCAST', 1]]), axisStatus('AIR')],
            myReports: [myReport('SKY_PRECIPITATION', 'RAIN', '2026-10-15T03:10:00Z')],
          }),
        ),
      }),
    );
    const sky = within(screen.getByTestId('field-axis-SKY_PRECIPITATION'));
    expect(sky.getByText('4명 · 비 3명, 흐림 1명')).toBeTruthy();
    expect(sky.getByText('많은 의견: 비')).toBeTruthy();
    expect(sky.getByText('11:50 최근 제보')).toBeTruthy();
    expect(sky.getByText('내 제보: 비')).toBeTruthy();
    expect(within(screen.getByTestId('field-axis-AIR')).getByText('아직 제보가 없어요')).toBeTruthy();

    fireEvent.press(screen.getByTestId('field-report-button'));
    expect(mockRouter.push).toHaveBeenCalledWith('/field-report');
  });

  it('제보가 없으면(EMPTY) 안내 + 제보 버튼', () => {
    renderHome(homeView({ fieldReports: { status: 'EMPTY', data: fieldReportStatus(), failure: null } }));
    const section = within(screen.getByTestId('section-field-reports'));
    expect(section.getByText('아직 이 동네 현장 제보가 없어요')).toBeTruthy();
    expect(section.getByText('지금 날씨 제보하기')).toBeTruthy();
  });

  it('실패(FAILED)면 다시 시도', () => {
    const { onRefresh } = renderHome(homeView({ fieldReports: { status: 'FAILED', data: null, failure: 'ERROR' } }));
    const section = within(screen.getByTestId('section-field-reports'));
    expect(section.getByText('불러오지 못했어요')).toBeTruthy();
    fireEvent.press(section.getByText('다시 시도'));
    expect(onRefresh).toHaveBeenCalled();
  });

  it('현재 동네가 아니면 제보 버튼을 끄고 이유를 보여준다', () => {
    renderHome(homeView({ fieldReports: ok(fieldReportStatus()) }), { viewingKind: 'interest' });
    expect(screen.getByTestId('field-report-button').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText('현장 제보는 현재 동네에서만 할 수 있어요')).toBeTruthy();
  });
});

describe('focus 강조 (07 §7.3)', () => {
  it('field-report: 섹션을 강조하고 현재 동네면 "나도 제보하기"를 강조한다', () => {
    renderHome(homeView({ fieldReports: ok(fieldReportStatus()) }), { focus: 'field-report' });
    expect(screen.getByTestId('section-field-reports').props.accessibilityState).toEqual({ selected: true });
    expect(screen.getByTestId('field-report-button-emphasized')).toHaveTextContent('나도 제보하기');
  });

  it('field-report지만 현재 동네가 아니면 섹션만 강조하고 버튼은 꺼 둔다', () => {
    renderHome(homeView({ fieldReports: ok(fieldReportStatus()) }), { focus: 'field-report', viewingKind: 'searched' });
    expect(screen.queryByTestId('field-report-button-emphasized')).toBeNull();
    expect(screen.getByTestId('field-report-button').props.accessibilityState.disabled).toBe(true);
  });

  it('hourly: 시간별 예보를 강조하고 weather가 준 다음 강수 시작 칸을 표시한다', () => {
    const base = homeView();
    const home: HomeView = {
      ...base,
      hourlyForecast: ok({
        ...base.hourlyForecast.data!,
        slots: [slot('2026-10-15', 22, 15), slot('2026-10-15', 23, 14)],
        nextPrecipitation: { startsAt: '2026-10-15T14:00:00Z', type: 'RAIN', phrase: null },
      }),
    };
    renderHome(home, { focus: 'hourly' });
    expect(screen.getByTestId('section-hourly').props.accessibilityState).toEqual({ selected: true });
    expect(screen.getAllByTestId('hourly-slot-precipitation-start')).toHaveLength(1);
    expect(screen.getByTestId('hourly-next-precipitation')).toHaveTextContent('23:00부터 비 예상');
    expect(screen.queryByTestId('field-report-button-emphasized')).toBeNull();
  });

  it('focus가 없으면 강조하지 않는다', () => {
    renderHome(homeView({ fieldReports: ok(fieldReportStatus()) }));
    expect(screen.getByTestId('section-hourly').props.accessibilityState).toBeUndefined();
    expect(screen.queryByTestId('hourly-next-precipitation')).toBeNull();
  });
});

describe('focus 스크롤', () => {
  it('가리킨 섹션의 자리가 잡히면 그 위치로 한 번 스크롤한다', () => {
    const scrollTo = jest.spyOn(ScrollView.prototype, 'scrollTo').mockImplementation(() => undefined);
    renderHome(homeView({ fieldReports: ok(fieldReportStatus()) }), { focus: 'field-report' });
    fireEvent(screen.getByTestId('focus-anchor-hourly'), 'layout', { nativeEvent: { layout: { x: 0, y: 900, width: 1, height: 1 } } });
    expect(scrollTo).not.toHaveBeenCalled();
    fireEvent(screen.getByTestId('focus-anchor-field-report'), 'layout', {
      nativeEvent: { layout: { x: 0, y: 640, width: 1, height: 1 } },
    });
    expect(scrollTo).toHaveBeenCalledWith({ y: 640 - FOCUS_TOP_MARGIN, animated: true });
    fireEvent(screen.getByTestId('focus-anchor-field-report'), 'layout', {
      nativeEvent: { layout: { x: 0, y: 700, width: 1, height: 1 } },
    });
    expect(scrollTo).toHaveBeenCalledTimes(1);
    scrollTo.mockRestore();
  });
});
