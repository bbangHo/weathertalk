import type { ReactNode } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { SafeAreaView, type Edge } from 'react-native-safe-area-context';

import { useWideLayout } from '@/platform/wideLayout';
import { colors, spacing } from '@/theme';

export type ScreenProps = {
  children: ReactNode;
  /** 헤더가 있는 화면은 위쪽 안전 영역을 헤더가 처리하므로 기본은 아래·좌우만 */
  edges?: Edge[];
  scroll?: boolean;
};

export function Screen({ children, edges = ['left', 'right', 'bottom'], scroll = true }: ScreenProps) {
  const wide = useWideLayout();
  return (
    <SafeAreaView style={styles.root} edges={edges}>
      {scroll ? (
        <ScrollView contentContainerStyle={[styles.content, wide && styles.contentWide]}>{children}</ScrollView>
      ) : (
        children
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  content: { padding: spacing.lg, gap: spacing.md },
  // 넓은 화면(PC 웹)에서 폼·목록이 끝까지 늘어나지 않게 가운데 열로
  contentWide: { width: '100%', maxWidth: 720, alignSelf: 'center' },
});
