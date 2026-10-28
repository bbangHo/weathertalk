// 섹션 상태 표시: 정상 / 데이터 없음 / 실패+다시 시도 (07 §1.4-2, §5 오류 표시 정책)
import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';

import { colors, radii, spacing } from '@/theme';

import { AppText } from './AppText';
import { Button } from './Button';

export type SectionStatus = 'ok' | 'empty' | 'failed';

export type SectionStateProps = {
  status: SectionStatus;
  children?: ReactNode;
  /** 데이터 없음 문구 (예: "아직 이 동네 글이 없어요") */
  emptyMessage?: string;
  /** 데이터 없음 상태에 붙일 행동(예: 글쓰기 버튼) */
  emptyAction?: ReactNode;
  failedMessage?: string;
  onRetry?: () => void | Promise<unknown>;
};

export function SectionState({
  status,
  children,
  emptyMessage = '아직 보여줄 내용이 없어요',
  emptyAction,
  failedMessage = '불러오지 못했어요',
  onRetry,
}: SectionStateProps) {
  if (status === 'ok') {
    return <>{children}</>;
  }
  if (status === 'empty') {
    return (
      <View style={styles.box} accessibilityRole="summary">
        <AppText tone="secondary">{emptyMessage}</AppText>
        {emptyAction}
      </View>
    );
  }
  return (
    <View style={styles.box} accessibilityRole="alert">
      <AppText tone="danger">{failedMessage}</AppText>
      {onRetry ? <Button title="다시 시도" variant="secondary" onPress={onRetry} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: {
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.lg,
    gap: spacing.md,
    alignItems: 'flex-start',
  },
});
