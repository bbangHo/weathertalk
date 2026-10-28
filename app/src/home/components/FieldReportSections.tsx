// SCR-01 3번 예보 vs 주민 차이 배지 · 6번 현장 제보 현황 (07 SCR-01, §1.2, §1.5, §7.3, 03 FR-WEA-05·12, BR-WEA-19)
// 배지는 weather가 표시하라고 한 축(badge=true)만, 받은 축·값·근거 시각 그대로 보여준다. 다수 의견도 weather 값이다.
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { AppText, Button, SectionState } from '@/components/ui';
import { AXES, AXIS_LABEL, divergenceBasisText, divergenceText, KIND_LABEL, NOT_CURRENT_REPORT } from '@/fieldReport/labels';
import type { AxisStatus, FieldReportStatus } from '@/fieldReport/types';
import type { ViewingKind } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { colors, radii, spacing } from '@/theme';

import { kstTimeText } from '../format';
import { uiStatus } from '../hourly';
import type { HomeView } from '../types';

import { Card } from './WeatherSections';

type Retry = () => void | Promise<unknown>;

export function DivergenceBadges({ home, onRetry }: { home: HomeView; onRetry: Retry }) {
  const section = home.divergence;
  // 현재 날씨가 실패해 조회하지 않은 경우는 현재 날씨 섹션이 이미 다시 시도를 보여준다.
  if (section.status === 'FAILED') {
    return section.failure === 'PREREQUISITE_FAILED' ? null : (
      <View testID="divergence-failed">
        <SectionState status="failed" failedMessage="예보와 주민 제보 비교를 불러오지 못했어요" onRetry={onRetry} />
      </View>
    );
  }
  const badges = section.status === 'OK' ? (section.data?.axes ?? []).filter((axis) => axis.badge) : [];
  if (badges.length === 0) {
    return null;
  }
  return (
    <View style={styles.badges} testID="divergence-badges">
      {badges.map((axis) => (
        <View key={axis.axis} style={styles.badge} testID={`divergence-${axis.axis}`} accessibilityRole="summary">
          <AppText variant="caption" tone="secondary">
            예보 vs 주민
          </AppText>
          <AppText>{divergenceText(axis)}</AppText>
          <AppText variant="caption" tone="secondary">
            {divergenceBasisText(axis)}
          </AppText>
        </View>
      ))}
    </View>
  );
}

function axisSummary(axis: AxisStatus): string {
  if (axis.reporterCount === 0) {
    return '아직 제보가 없어요';
  }
  const counts = axis.counts.map((count) => `${KIND_LABEL[count.kind]} ${count.reporterCount}명`).join(', ');
  return `${axis.reporterCount}명 · ${counts}`;
}

function StatusBody({ status }: { status: FieldReportStatus }) {
  return (
    <View style={styles.body}>
      {AXES.map((axisName) => {
        const axis = status.axes.find((item) => item.axis === axisName);
        const mine = status.myReports.find((report) => report.axis === axisName && !report.canceledAt);
        const latest = axis ? kstTimeText(axis.latestReportedAt) : null;
        return (
          <View key={axisName} style={styles.axis} testID={`field-axis-${axisName}`}>
            <View style={styles.row}>
              <AppText tone="secondary">{AXIS_LABEL[axisName]}</AppText>
              <AppText>{axis ? axisSummary(axis) : '정보 없음'}</AppText>
            </View>
            {axis?.majorityKind ? (
              <AppText variant="caption">{`많은 의견: ${KIND_LABEL[axis.majorityKind]}`}</AppText>
            ) : null}
            {latest ? <AppText variant="caption" tone="secondary">{`${latest} 최근 제보`}</AppText> : null}
            {mine ? <AppText variant="caption">{`내 제보: ${KIND_LABEL[mine.kind]}`}</AppText> : null}
          </View>
        );
      })}
    </View>
  );
}

export function FieldReportSection({
  home,
  viewingKind,
  emphasized,
  onRetry,
}: {
  home: HomeView;
  viewingKind: ViewingKind;
  /** 현장 제보 강수 알림으로 들어왔다(07 §7.3) — 현재 동네면 "나도 제보하기"를 강조한다 */
  emphasized: boolean;
  onRetry: Retry;
}) {
  const section = home.fieldReports;
  const isCurrent = viewingKind === 'current';
  const strong = emphasized && isCurrent;
  return (
    <Card title="주민 현장 제보" testID="section-field-reports" emphasized={emphasized}>
      <SectionState status={uiStatus(section)} emptyMessage="아직 이 동네 현장 제보가 없어요" onRetry={onRetry}>
        {section.data ? <StatusBody status={section.data} /> : null}
      </SectionState>
      <Button
        title={strong ? '나도 제보하기' : '지금 날씨 제보하기'}
        variant={strong ? 'primary' : 'secondary'}
        disabled={!isCurrent}
        onPress={() => router.push(routes.fieldReport())}
        testID={strong ? 'field-report-button-emphasized' : 'field-report-button'}
      />
      {!isCurrent ? (
        <AppText variant="caption" tone="secondary">
          {NOT_CURRENT_REPORT}
        </AppText>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  badges: { gap: spacing.sm },
  badge: {
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.warning,
    padding: spacing.md,
    gap: 2,
  },
  body: { gap: spacing.md },
  axis: { gap: 2 },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.md },
});
