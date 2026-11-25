// SCR-12 첫 실행 안내 (07 SCR-12, §2 흐름·규칙 2·3·5). 한 장: 서비스 소개 + 위치 사용 이유.
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { AppText, Button } from '@/components/ui';
import { requestLocationPermission } from '@/location/deviceLocation';
import { judgeCurrentNeighborhood } from '@/location/judgeCurrentNeighborhood';
import { clearLocationDeclined, recordLocationDeclined } from '@/location/prepromptHistory';
import { viewingStore } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { colors, radii, spacing } from '@/theme';

export function WelcomeScreen() {
  const goSearch = () => router.replace(routes.neighborhoodSearch({ fromOnboarding: true }));

  const chooseLocation = async () => {
    // 어느 쪽을 고르든 첫 실행 완료를 남긴다 — 권한 창에서 앱이 꺼져도 다시 묻지 않게 먼저 저장한다.
    await viewingStore.completeOnboarding();
    const { permission } = await requestLocationPermission();
    if (permission !== 'granted') {
      await recordLocationDeclined().catch(() => undefined);
      goSearch();
      return;
    }
    await clearLocationDeclined().catch(() => undefined);
    const result = await judgeCurrentNeighborhood({ store: viewingStore });
    if (result.status === 'resolved') {
      router.replace(routes.home());
    } else {
      goSearch();
    }
  };

  const later = async () => {
    await viewingStore.completeOnboarding();
    await recordLocationDeclined().catch(() => undefined);
    goSearch();
  };

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.body}>
        <AppText variant="title">날씨톡톡</AppText>
        <AppText variant="heading">나만의 체감 날씨</AppText>
        <AppText tone="secondary">
          오늘 날씨가 나에게는 어떻게 느껴질지, 나처럼 더위·추위를 타는 사람들의 기록으로 보여드려요.
        </AppText>
        <View style={styles.notice}>
          <AppText variant="button">위치는 동네 판정에만 쓰고 저장하지 않아요</AppText>
          <AppText variant="caption" tone="secondary">
            지금 있는 동네를 찾은 뒤 위치 정보는 바로 버려요. 위치 없이 동네를 직접 골라도 돼요.
          </AppText>
        </View>
      </View>
      <View style={styles.actions}>
        <Button title="위치 사용하기" onPress={chooseLocation} testID="welcome-use-location" />
        <Button title="나중에 (동네 직접 고르기)" variant="secondary" onPress={later} testID="welcome-later" />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background, padding: spacing.xl, justifyContent: 'space-between' },
  body: { gap: spacing.md, marginTop: spacing.xxl },
  notice: {
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    padding: spacing.lg,
    gap: spacing.xs,
  },
  actions: { gap: spacing.sm },
});
