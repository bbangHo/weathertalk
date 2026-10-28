// 가운데 "남기기" 버튼: 화면이 아니라 선택 시트를 연다 (07 §4 표)
import Ionicons from '@expo/vector-icons/Ionicons';
import { router, type Href } from 'expo-router';
import { useState, type ComponentProps } from 'react';
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { AppText } from '@/components/ui';
import { routes } from '@/navigation/routes';
import { colors, radii, spacing } from '@/theme';

import { TAB_ICON_SIZE, tabLabelStyle } from './tabBar';

type IconName = ComponentProps<typeof Ionicons>['name'];

const CHOICES: readonly { label: string; description: string; icon: IconName; href: Href }[] = [
  { label: '체감 기록', description: '지금 느끼는 날씨를 남겨요', icon: 'thermometer-outline', href: routes.feelingRecord() },
  {
    label: '현장 제보',
    description: '비·맑음·미세먼지 같은 지금 상태를 알려요',
    icon: 'megaphone-outline',
    href: routes.fieldReport(),
  },
  { label: '글쓰기', description: '우리 동네에 글을 남겨요', icon: 'create-outline', href: routes.postEditor() },
];

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
          <Ionicons name="add" size={TAB_ICON_SIZE - 2} color={colors.onPrimary} />
        </View>
        <Text style={[tabLabelStyle, styles.tabLabel]}>남기기</Text>
      </Pressable>

      <Modal visible={open} transparent animationType="slide" onRequestClose={() => setOpen(false)}>
        <Pressable style={styles.backdrop} accessibilityLabel="닫기" onPress={() => setOpen(false)} />
        <View style={[styles.sheet, { paddingBottom: spacing.lg + insets.bottom }]} testID="leave-choice-sheet">
          <View style={styles.handle} />
          <View style={styles.sheetHeader}>
            <AppText variant="heading">무엇을 남길까요?</AppText>
            <Pressable accessibilityRole="button" accessibilityLabel="시트 닫기" hitSlop={8} onPress={() => setOpen(false)}>
              <Ionicons name="close" size={TAB_ICON_SIZE} color={colors.textSecondary} />
            </Pressable>
          </View>
          {CHOICES.map((choice) => (
            <Pressable
              key={choice.label}
              accessibilityRole="button"
              style={({ pressed }) => [styles.choice, pressed && styles.choicePressed]}
              onPress={() => {
                setOpen(false);
                router.push(choice.href);
              }}
            >
              <View style={styles.choiceIcon}>
                <Ionicons name={choice.icon} size={22} color={colors.primary} />
              </View>
              <View style={styles.choiceText}>
                <AppText variant="button">{choice.label}</AppText>
                <AppText variant="caption" tone="secondary">
                  {choice.description}
                </AppText>
              </View>
              <Ionicons name="chevron-forward" size={18} color={colors.textDisabled} />
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
    width: 28,
    height: 28,
    borderRadius: radii.pill,
    backgroundColor: colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabLabel: { color: colors.textSecondary },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.3)' },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radii.lg,
    borderTopRightRadius: radii.lg,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.sm,
    gap: spacing.sm,
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: radii.pill,
    backgroundColor: colors.border,
    marginBottom: spacing.xs,
  },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
  },
  choice: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    padding: spacing.md,
    borderRadius: radii.md,
    backgroundColor: colors.background,
  },
  choicePressed: { backgroundColor: colors.surfaceMuted },
  choiceIcon: {
    width: 40,
    height: 40,
    borderRadius: radii.pill,
    backgroundColor: colors.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  choiceText: { flex: 1, gap: 2 },
});
