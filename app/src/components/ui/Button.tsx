// 쓰기 버튼은 요청 중 비활성화한다 (07 §5 중복 제출 방지).
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { colors, radii, spacing } from '@/theme';

import { AppText } from './AppText';

export type ButtonProps = {
  title: string;
  /** Promise를 돌려주면 끝날 때까지 자동으로 비활성·로딩 표시한다. */
  onPress: () => void | Promise<unknown>;
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
  /** 바깥에서 요청 중임을 알려줄 때(예: mutation.isPending) */
  pending?: boolean;
  testID?: string;
};

export function Button({ title, onPress, variant = 'primary', disabled, pending, testID }: ButtonProps) {
  const [running, setRunning] = useState(false);
  const mounted = useRef(true);
  useEffect(
    () => () => {
      mounted.current = false;
    },
    [],
  );

  const busy = running || Boolean(pending);
  const inactive = busy || Boolean(disabled);

  const handlePress = useCallback(() => {
    if (inactive) {
      return;
    }
    const result = onPress();
    if (result && typeof (result as Promise<unknown>).then === 'function') {
      setRunning(true);
      (result as Promise<unknown>)
        .catch(() => {
          // 오류 표시는 호출한 화면 책임이다. 여기서는 비활성만 푼다.
        })
        .finally(() => {
          if (mounted.current) {
            setRunning(false);
          }
        });
    }
  }, [inactive, onPress]);

  const isPrimary = variant === 'primary';
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ disabled: inactive, busy }}
      disabled={inactive}
      onPress={handlePress}
      style={({ pressed }) => [
        styles.base,
        isPrimary ? styles.primary : styles.secondary,
        pressed && isPrimary && styles.primaryPressed,
        inactive && styles.inactive,
      ]}
    >
      <View style={styles.content}>
        {busy ? <ActivityIndicator size="small" color={isPrimary ? colors.onPrimary : colors.primary} /> : null}
        <AppText variant="button" tone={inactive ? 'disabled' : isPrimary ? 'onPrimary' : 'primary'}>
          {title}
        </AppText>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    minHeight: 48,
    borderRadius: radii.md,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  primary: { backgroundColor: colors.primary },
  primaryPressed: { backgroundColor: colors.primaryPressed },
  secondary: { backgroundColor: colors.surfaceMuted },
  inactive: { backgroundColor: colors.disabledBackground },
  content: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
});
