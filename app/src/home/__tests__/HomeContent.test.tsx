// SCR-01 날씨 섹션 표시: 섹션 상태, 값 없음 "정보 없음", 시간별 중복 유지·날짜 경계, 출처 표시
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { homeView, sensitivityFeelingsOk, slot } from '@/test-utils/homeFixtures';

import { HomeContent, type HomeContentProps } from '../components/HomeContent';
import { weatherIconOf } from '../components/WeatherIcon';
import { buildHourlyItems } from '../hourly';
import type { CurrentObservation, HomeView } from '../types';

function renderHome(home: HomeView, props: Partial<HomeContentProps> = {}) {
  const onRefresh = jest.fn();
  const onOpenSwitch = jest.fn();
  // A2부터 홈 본문에 체감 피드백·보정 제안처럼 서버 캐시를 쓰는 영역이 있어 쿼리 제공자로 감싼다.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider initialMetrics={{ frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 0, left: 0, right: 0, bottom: 0 } }}>
        <HomeContent
          home={home}
          viewingKind="current"
          cachedSavedAt={null}
          refreshFailed={false}
          refreshing={false}
          onRefresh={onRefresh}
          onOpenSwitch={onOpenSwitch}
          {...props}
        />
      </SafeAreaProvider>
    </QueryClientProvider>,
  );
  return { onRefresh, onOpenSwitch };
}

function currentWith(patch: Partial<CurrentObservation>): HomeView {
  const base = homeView();
  return { ...base, currentWeather: { ...base.currentWeather, data: { ...base.currentWeather.data!, ...patch } } };
}

describe('홈 헤더', () => {
  it('보는 동네 이름과 현재 동네 표시, 누르면 동네 전환', () => {
    const { onOpenSwitch } = renderHome(homeView());
    expect(screen.getByText('역삼1동')).toBeTruthy();
    expect(screen.getByText('현재 동네')).toBeTruthy();
    fireEvent.press(screen.getByTestId('home-header'));
    expect(onOpenSwitch).toHaveBeenCalled();
  });

  it('알림 종을 누르면 알림 설정으로 간다', () => {
    const onOpenNotifications = jest.fn();
    renderHome(homeView(), { onOpenNotifications });
    fireEvent.press(screen.getByTestId('home-notifications'));
    expect(onOpenNotifications).toHaveBeenCalled();
  });

  it('관심 동네·둘러보는 동네 표시', () => {
    renderHome(homeView(), { viewingKind: 'interest' });
    expect(screen.getByText('관심 동네')).toBeTruthy();
  });
});

/** 현재 날씨 세부(체감온도·습도·바람·자외선·대기질·에어코리아 출처)는 접혀 있어 펼친 뒤 확인한다. */
function openCurrentDetails() {
  fireEvent.press(screen.getByTestId('current-weather-toggle'));
}

describe('현재 날씨', () => {
  it('처음엔 기온·하늘·관측 시각만 보이고, 자세히를 누르면 세부가 펼쳐진다', () => {
    renderHome(homeView());
    const section = within(screen.getByTestId('section-current-weather'));
    expect(section.getByText('18°')).toBeTruthy();
    expect(section.getByTestId('observation-time')).toBeTruthy();
    expect(section.queryByTestId('current-weather-details')).toBeNull();
    expect(screen.getByTestId('current-weather-toggle').props.accessibilityState).toMatchObject({ expanded: false });
    // 요약 줄: 서버 강수 문구가 있으면 그대로("강수 강수 없음"처럼 겹치지 않게), 없으면 "강수 " + 값
    expect(section.getByText(/^체감 17\.2° · /)).toBeTruthy();
    expect(section.queryByText(/강수 강수/)).toBeNull();

    openCurrentDetails();
    expect(section.getByTestId('current-weather-details')).toBeTruthy();
    expect(screen.getByTestId('current-weather-toggle').props.accessibilityState).toMatchObject({ expanded: true });
  });

  it('기온·하늘(낮밤은 서버 값)·체감온도·자외선 5단계 문구·관측 시각을 보여준다', () => {
    renderHome(currentWith({ dayNight: 'NIGHT' }));
    openCurrentDetails();
    const section = within(screen.getByTestId('section-current-weather'));
    expect(section.getByText('18°')).toBeTruthy();
    // 하늘 상태는 해요체로(A′): 맑은 밤
    expect(section.getByText('맑은 밤이에요')).toBeTruthy();
    expect(section.getByText('17.2°')).toBeTruthy();
    expect(section.getByText('보통 (4)')).toBeTruthy();
    expect(section.getByText('모자, 선글라스 이용')).toBeTruthy();
    // 관측 시각은 한국 시각(00:00Z → 09:00)
    expect(section.getByTestId('observation-time').props.children).toBe('09:00 기준');
  });

  it('대기질 값이 없으면 "정보 없음" — 좋음으로 채우지 않는다', () => {
    renderHome(currentWith({ airQuality: null, uv: null }));
    openCurrentDetails();
    const section = within(screen.getByTestId('section-current-weather'));
    expect(section.getAllByText('정보 없음 · 정보 없음')).toHaveLength(2);
    expect(section.queryByText(/좋음/)).toBeNull();
    // 자외선도 값이 없으면 정보 없음(대기질 등급을 쓰지 않는다)
    expect(section.getAllByText('정보 없음').length).toBeGreaterThan(0);
  });

  it('PM2.5만 결측이면 그 항목만 정보 없음, 수치는 받은 그대로', () => {
    renderHome(homeView());
    openCurrentDetails();
    const section = within(screen.getByTestId('section-current-weather'));
    expect(section.getByText('보통 · 35㎍/㎥')).toBeTruthy();
    expect(section.getByText('정보 없음 · 정보 없음')).toBeTruthy();
  });

  it('지연 신선도·예보 대체를 그대로 알린다', () => {
    renderHome(currentWith({ weatherFreshness: 'DELAYED', sources: { TEMPERATURE: 'FORECAST' } }));
    expect(screen.getByTestId('observation-time').props.children).toBe(
      '09:00 기준 · 업데이트가 늦어지고 있어요 · 일부 값은 예보예요',
    );
  });

  it('에어코리아·기상청 출처를 표시한다', () => {
    renderHome(homeView());
    // 대기질 값은 세부에만 있어 출처도 세부를 펼치면 함께 보인다. 기상청 출처는 홈 맨 아래에 늘 있다.
    expect(screen.queryByTestId('air-attribution')).toBeNull();
    openCurrentDetails();
    expect(screen.getByTestId('air-attribution').props.children).toContain('에어코리아');
    expect(screen.getByTestId('air-attribution').props.children).toContain('오류가 있을 수 있어요');
    expect(screen.getByTestId('kma-attribution').props.children).toContain('기상청');
  });
});

describe('섹션 상태 (07 §1.4)', () => {
  it('데이터 없음과 실패를 다르게 보여주고, 실패 섹션만 다시 시도(홈 전체 재조회)', () => {
    const base = homeView();
    const { onRefresh } = renderHome({
      ...base,
      hourlyForecast: { status: 'EMPTY', data: { ...base.hourlyForecast.data!, slots: [] }, failure: null },
      dailyForecast: { status: 'FAILED', data: null, failure: 'TIMEOUT' },
    });

    expect(within(screen.getByTestId('section-hourly')).getByText('시간별 예보가 아직 없어요')).toBeTruthy();
    const daily = within(screen.getByTestId('section-daily'));
    expect(daily.getByText('불러오지 못했어요')).toBeTruthy();
    // 다른 섹션은 그대로 보인다
    expect(within(screen.getByTestId('section-current-weather')).getByText('18°')).toBeTruthy();

    fireEvent.press(daily.getByText('다시 시도'));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('현재 날씨 실패도 그 섹션만 실패 표시', () => {
    renderHome({ ...homeView(), currentWeather: { status: 'FAILED', data: null, failure: 'ERROR' } });
    expect(within(screen.getByTestId('section-current-weather')).getByText('불러오지 못했어요')).toBeTruthy();
    expect(screen.getAllByTestId('hourly-slot')).toHaveLength(3);
  });
});

describe('날씨 카드(A′)', () => {
  afterEach(() => jest.useRealTimers());

  it('오늘 칸이 있으면 최고·최저를 그대로, 없으면 그 줄을 그리지 않는다', () => {
    jest.useFakeTimers({ now: new Date('2026-10-15T03:00:00Z') });
    renderHome(homeView());
    const hiLo = within(screen.getByTestId('today-hi-lo'));
    expect(hiLo.getByText('최고 20°')).toBeTruthy();
    expect(hiLo.getByText('최저 10°')).toBeTruthy();
  });

  it('오늘이 아닌 날짜뿐이면 최고·최저 줄이 없다', () => {
    jest.useFakeTimers({ now: new Date('2026-10-20T03:00:00Z') });
    renderHome(homeView());
    expect(screen.queryByTestId('today-hi-lo')).toBeNull();
  });

  it('카드 안 한 줄에 기준 그룹 체감과 출처를 보여 준다', () => {
    renderHome(homeView({ sensitivityFeelings: sensitivityFeelingsOk() }));
    const row = screen.getByTestId('neighbors-summary');
    expect(row.props.accessibilityLabel).toMatch(/^보통 기준으로는 /);
    expect(row.props.accessibilityLabel).toContain('딱 좋아요');
    expect(within(row).getByText('비슷한 날씨의 기록 · 이 동네 기록이 아직 적어요')).toBeTruthy();
  });

  it('다음 시간 예보를 최대 6칸 보여 준다', () => {
    renderHome(homeView());
    expect(within(screen.getByTestId('current-weather-hourly-strip')).getByText('22시')).toBeTruthy();
  });
});

describe('시간별 예보', () => {
  it('같은 시(時)가 다른 날짜로 와도 지우지 않고, 날짜가 바뀌는 곳에 표시를 넣는다', () => {
    const slots = [slot('2026-10-15', 23, 14), slot('2026-10-16', 0, 13), slot('2026-10-16', 23, 12), slot('2026-10-17', 23, 11)];
    expect(buildHourlyItems(slots).map((item) => (item.kind === 'date' ? `[${item.date}]` : item.slot.hour))).toEqual([
      23,
      '[2026-10-16]',
      0,
      23,
      '[2026-10-17]',
      23,
    ]);

    const base = homeView();
    renderHome({ ...base, hourlyForecast: { ...base.hourlyForecast, data: { ...base.hourlyForecast.data!, slots } } });
    // 날씨 카드의 앞 6시간 줄에도 같은 시각이 나오므로 시간별 예보 섹션 안에서만 센다.
    const hourly = within(screen.getByTestId('section-hourly'));
    expect(screen.getAllByTestId('hourly-slot')).toHaveLength(4);
    expect(hourly.getAllByText('23시')).toHaveLength(3);
    expect(screen.getAllByTestId('hourly-date-marker')).toHaveLength(2);
    expect(screen.getByText('10월 16일 (금)')).toBeTruthy();
  });

  it('완전히 같은 칸이 두 번 와도 받은 그대로 둘 다 보여준다', () => {
    const slots = [slot('2026-10-15', 9, 14), slot('2026-10-15', 9, 14)];
    const base = homeView();
    renderHome({ ...base, hourlyForecast: { ...base.hourlyForecast, data: { ...base.hourlyForecast.data!, slots } } });
    expect(within(screen.getByTestId('section-hourly')).getAllByText('9시')).toHaveLength(2);
  });
});

describe('날씨 아이콘', () => {
  it('강수가 있으면 강수 아이콘, 하늘·강수 모두 없으면 정보 없음 아이콘', () => {
    expect(weatherIconOf('CLEAR', 'NIGHT').name).toBe('moon');
    expect(weatherIconOf('CLEAR', 'DAY', 'RAIN').name).toBe('rainy');
    expect(weatherIconOf('OVERCAST', null, 'SNOW').name).toBe('snow');
    expect(weatherIconOf(null, null).name).toBe('cloud-offline-outline');

    renderHome(currentWith({ sky: null, precipitationType: null }));
    expect(screen.getByTestId('current-weather-icon').props.accessibilityLabel).toBe('정보 없음');
  });
});

describe('주간 예보', () => {
  it('빈 날은 정보 없음', () => {
    const base = homeView();
    const day = base.dailyForecast.data!.days[0];
    renderHome({
      ...base,
      dailyForecast: {
        ...base.dailyForecast,
        data: {
          ...base.dailyForecast.data!,
          days: [day, { ...day, date: '2026-10-16', source: null, minTemperature: null, maxTemperature: null }],
        },
      },
    });
    const days = screen.getAllByTestId('daily-day');
    expect(within(days[0]).getByLabelText('오전 맑음')).toBeTruthy();
    expect(within(days[0]).getByLabelText('오후 구름많음')).toBeTruthy();
    expect(within(days[0]).getByText('10°')).toBeTruthy();
    expect(within(days[0]).getByText('20°')).toBeTruthy();
    expect(within(days[1]).getByText('정보 없음')).toBeTruthy();
  });
});

describe('마지막 홈 응답 표시', () => {
  it('최신 조회 실패 시 저장분임을 알리고 다시 시도할 수 있다', () => {
    const { onRefresh } = renderHome(homeView(), { cachedSavedAt: '2026-10-15T00:30:00Z', refreshFailed: true });
    const notice = within(screen.getByTestId('cached-home-notice'));
    expect(notice.getByText('최신 날씨를 불러오지 못해 09:30에 받은 내용을 보여줘요')).toBeTruthy();
    fireEvent.press(notice.getByText('다시 시도'));
    expect(onRefresh).toHaveBeenCalled();
  });
});
