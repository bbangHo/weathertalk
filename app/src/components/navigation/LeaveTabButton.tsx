// 가운데 "남기기" 버튼: 화면이 아니라 선택 시트를 연다 (07 §4 표)
import { router } from 'expo-router';
import { useState } from 'react';
import { Modal, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { AppText } from '@/components/ui';
import { routes } from '@/navigation/routes';
import { colors, radii, spacing } from '@/theme';

const CHOICES = [
  { label: '체감 기록', description: '지금 느끼는 날씨를 남겨요', href: routes.feelingRecord() },
  { label: '현장 제보', description: '비·맑음·미세먼지 같은 지금 상태를 알려요', href: routes.fieldReport() },
  { label: '글쓰기', description: '우리 동네에 글을 남겨요', href: routes.postEditor() },
] as const;

export function LeaveTabButton() {
  const [open, setOpen] = useState(false);
  const insets = useSafeAreaInsets();

  return (
    <>
      <Pressable
        testID="leave-tab-button"
        accessibilityRole="button"
        accessibilityLabel="남기기"
        onPress={() => setOpen(true)}
        style={styles.tabButton}
      >
        <View style={styles.circle}>
          <AppText variant="heading" tone="onPrimary">
            +
          </AppText>
        </View>
        <AppText variant="caption" tone="secondary">
          남기기
        </AppText>
      </Pressable>

      <Modal visible={open} transparent animationType="slide" onRequestClose={() => setOpen(false)}>
        <Pressable style={styles.backdrop} accessibilityLabel="닫기" onPress={() => setOpen(false)} />
        <View style={[styles.sheet, { paddingBottom: spacing.lg + insets.bottom }]} testID="leave-choice-sheet">
          <AppText variant="heading">무엇을 남길까요?</AppText>
          {CHOICES.map((choice) => (
            <Pressable
              key={choice.label}
              accessibilityRole="button"
              style={styles.choice}
              onPress={() => {
                setOpen(false);
                router.push(choice.href);
              }}
            >
              <AppText variant="button">{choice.label}</AppText>
              <AppText variant="caption" tone="secondary">
                {choice.description}
              </AppText>
            </Pressable>
          ))}
        </View>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  tabButton: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 2 },
  circle: {
    width: 36,
    height: 36,
    borderRadius: radii.pill,
    backgroundColor: colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.3)' },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radii.lg,
    borderTopRightRadius: radii.lg,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  choice: {
    padding: spacing.md,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.border,
    gap: 2,
  },
});
