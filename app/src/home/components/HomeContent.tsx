// SCR-01 홈 화면 본문 (07 SCR-01). 데이터 조회와 분리한 표시 전용 컴포넌트.
import { Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';

import { AppText, Button } from '@/components/ui';
import type { ViewingKind } from '@/neighborhood/viewingStore';
import { colors, radii, spacing } from '@/theme';

import { kstTimeText } from '../format';
import type { HomeView } from '../types';

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
}: HomeContentProps) {
  const neighborhood = home.neighborhood;
  // 섹션별 다시 시도는 home 전체 재조회로 한다(07 §1.4-5).
  const retry = () => onRefresh();
  return (
    <ScrollView
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
      {/* 민감도별 체감·피드백·차이 배지·옷 추천·어제 비교·현장 제보 현황은 A2~A4에서 이 자리에 넣는다. */}
      <HourlyForecastSection section={home.hourlyForecast} onRetry={retry} />
      <DailyForecastSection section={home.dailyForecast} onRetry={retry} />
      {/* 동네 인기글은 A3에서 이 자리에 넣는다. */}
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
