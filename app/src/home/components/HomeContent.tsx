// SCR-01 홈 화면 본문 (07 SCR-01, §7.3 섹션 강조 이동). 데이터 조회와 분리한 표시 전용 컴포넌트.
import { useCallback, useEffect, useRef } from 'react';
import { type LayoutChangeEvent, Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';

import { AppText, Button } from '@/components/ui';
import type { ViewingKind } from '@/neighborhood/viewingStore';
import type { HomeFocus } from '@/navigation/routes';
import { colors, radii, spacing } from '@/theme';

import { focusScrollY } from '../focus';
import { kstTimeText } from '../format';
import type { HomeView } from '../types';

import { DivergenceBadges, FieldReportSection } from './FieldReportSections';
import { PopularPostsSection } from './PopularPostsSection';
import { CalibrationOfferCard, OutfitSection, SensitivityFeelingsSection, YesterdaySection } from './FeelingSections';
import { CurrentWeatherSection, DailyForecastSection, HourlyForecastSection, WeatherAttribution } from './WeatherSections';

export type HomeContentProps = {
  home: HomeView;
  viewingKind: ViewingKind;
  /** 마지막 홈 응답(기기 저장분)을 보여주는 중이면 저장 시각 */
  cachedSavedAt: string | null;
  /** 최신 조회가 실패해 저장분을 보여주는 중 */
  refreshFailed: boolean;
  refreshing: boolean;
  onRefresh: () => void;
  onOpenSwitch: () => void;
  /** 회원이면 피드백을 바로 저장하고 보정 제안 카드를 보여준다. 기본 비회원 */
  isMember?: boolean;
  /** 알림 등으로 가리킨 섹션(경로 파라미터 focus). 자리가 잡히면 한 번 스크롤하고 강조한다 */
  focus?: HomeFocus | null;
};

const KIND_LABEL: Record<ViewingKind, string> = {
  current: '📍 현재 동네',
  interest: '★ 관심 동네',
  searched: '둘러보는 동네',
};

export function HomeContent({
  home,
  viewingKind,
  cachedSavedAt,
  refreshFailed,
  refreshing,
  onRefresh,
  onOpenSwitch,
  isMember = false,
  focus = null,
}: HomeContentProps) {
  const scrollRef = useRef<ScrollView>(null);
  const feelingsY = useRef(0);
  const positions = useRef<Partial<Record<HomeFocus, number>>>({});
  const scrolledFor = useRef<HomeFocus | null>(null);
  // 섹션 자리는 그려진 뒤에야 알 수 있어, 자리를 받을 때마다 아직 못 간 강조 대상이면 이동한다.
  const scrollToFocus = useCallback(() => {
    if (focus === null) {
      scrolledFor.current = null;
      return;
    }
    if (scrolledFor.current === focus) {
      return;
    }
    const y = focusScrollY(focus, positions.current);
    if (y === null) {
      return;
    }
    scrolledFor.current = focus;
    scrollRef.current?.scrollTo({ y, animated: true });
  }, [focus]);
  useEffect(scrollToFocus, [scrollToFocus]);
  const onFieldReportLayout = useCallback(
    (event: LayoutChangeEvent) => {
      positions.current['field-report'] = event.nativeEvent.layout.y;
      scrollToFocus();
    },
    [scrollToFocus],
  );
  const onHourlyLayout = useCallback(
    (event: LayoutChangeEvent) => {
      positions.current.hourly = event.nativeEvent.layout.y;
      scrollToFocus();
    },
    [scrollToFocus],
  );
  const neighborhood = home.neighborhood;
  // 섹션별 다시 시도는 home 전체 재조회로 한다(07 §1.4-5).
  const retry = () => onRefresh();
  return (
    <ScrollView
      ref={scrollRef}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
      testID="home-scroll"
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${neighborhood.fullName}, 동네 바꾸기`}
        onPress={onOpenSwitch}
        style={styles.header}
        testID="home-header"
      >
        <AppText variant="caption" tone="secondary">
          {KIND_LABEL[viewingKind]}
        </AppText>
        <View style={styles.headerName}>
          <AppText variant="title">{neighborhood.eupmyeondongName}</AppText>
          <AppText tone="secondary">▾</AppText>
        </View>
        <AppText variant="caption" tone="secondary">
          {`${neighborhood.sidoName} ${neighborhood.sigunguName}`}
        </AppText>
      </Pressable>

      {cachedSavedAt ? (
        <View style={styles.notice} testID="cached-home-notice">
          <AppText variant="caption" tone={refreshFailed ? 'danger' : 'secondary'}>
            {refreshFailed
              ? `최신 날씨를 불러오지 못해 ${kstTimeText(cachedSavedAt) ?? ''}에 받은 내용을 보여줘요`
              : '최신 날씨를 불러오는 중이에요'}
          </AppText>
          {refreshFailed ? <Button title="다시 시도" variant="secondary" onPress={retry} /> : null}
        </View>
      ) : null}

      <CurrentWeatherSection section={home.currentWeather} onRetry={retry} />
      {isMember ? <CalibrationOfferCard section={home.calibrationOffer} /> : null}
      <View onLayout={(event) => (feelingsY.current = event.nativeEvent.layout.y)}>
        <SensitivityFeelingsSection home={home} viewingKind={viewingKind} isMember={isMember} onRetry={retry} />
      </View>
      <DivergenceBadges home={home} onRetry={retry} />
      <OutfitSection
        home={home}
        onRetry={retry}
        onPickSensitivity={() => scrollRef.current?.scrollTo({ y: feelingsY.current, animated: true })}
      />
      <YesterdaySection home={home} onRetry={retry} />
      <View onLayout={onFieldReportLayout} testID="focus-anchor-field-report">
        <FieldReportSection
          home={home}
          viewingKind={viewingKind}
          emphasized={focus === 'field-report'}
          onRetry={retry}
        />
      </View>
      <View onLayout={onHourlyLayout} testID="focus-anchor-hourly">
        <HourlyForecastSection section={home.hourlyForecast} onRetry={retry} emphasized={focus === 'hourly'} />
      </View>
      <DailyForecastSection section={home.dailyForecast} onRetry={retry} />
      <PopularPostsSection home={home} viewingKind={viewingKind} onRetry={retry} />
      <WeatherAttribution />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.lg, gap: spacing.md },
  header: { gap: 2, paddingVertical: spacing.xs },
  headerName: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  notice: {
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
    gap: spacing.sm,
  },
});
