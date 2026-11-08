// 데모 모드 표시: 화면 맨 위 띠(내용을 가리지 않게 자리를 차지한다). 보이는 글·기록이 실제가 아닌 예시임을 늘 밝히고, '나가기'로 실제 앱으로 돌아간다.
import { Pressable, StyleSheet, View } from 'react-native';

import { AppText } from '@/components/ui';
import { colors, spacing } from '@/theme';

import { DEMO_MODE, exitDemo } from './demoMode';

export function DemoBadge() {
  if (!DEMO_MODE) {
    return null;
  }
  return (
    <View style={styles.bar} testID="demo-badge" accessibilityRole="alert">
      <AppText variant="caption" style={styles.label}>
        데모 화면 · 글과 기록은 모두 예시예요
      </AppText>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="데모 나가기"
        hitSlop={8}
        onPress={exitDemo}
        style={styles.exit}
        testID="demo-exit"
      >
        <AppText variant="caption" style={styles.exitLabel}>
          나가기
        </AppText>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: 6,
    backgroundColor: colors.textPrimary,
  },
  label: { color: colors.onPrimary, fontWeight: '700' },
  exit: { paddingHorizontal: 10, paddingVertical: 2, borderRadius: 999, backgroundColor: colors.warning },
  exitLabel: { color: colors.onPrimary, fontWeight: '700' },
});
