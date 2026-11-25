// 자동 판정 제안 (ADR 0009): 앱 진입·포그라운드 복귀·로그인 직후 판정한 동네가 보는 동네와 다르면 화면 위에 묻는다.
// 누르기 전에는 보는 동네·현재 동네를 바꾸지 않고 서버로도 보내지 않는다. 이름은 판정 결과(앱의 대표점 데이터)로 보여준다.
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { api } from '@/api';
import { AppText, Button } from '@/components/ui';
import { adoptNeighborhood } from '@/location/judgeCurrentNeighborhood';
import { routes } from '@/navigation/routes';
import { sessionStore } from '@/session/sessionStore';
import { colors, radii, spacing } from '@/theme';

import { useViewingState, viewingStore } from './viewingStore';

export function NeighborhoodSuggestion() {
  const { suggestion } = useViewingState();
  const insets = useSafeAreaInsets();
  if (!suggestion) {
    return null;
  }

  const accept = async () => {
    const member = sessionStore.getState().status === 'member' ? { api } : null;
    // 서버 현재 동네 반영이 실패해도 기기에는 반영된다(다음 쓰기 때 서버도 맞춰진다).
    await adoptNeighborhood(viewingStore, suggestion.id, member).catch(() => undefined);
    router.navigate(routes.home());
  };

  return (
    <View style={[styles.banner, { top: insets.top + spacing.sm }]} testID="neighborhood-suggestion">
      <AppText variant="button">{`지금 위치는 ${suggestion.eupmyeondongName}이에요. 이 동네 날씨 볼까요?`}</AppText>
      <View style={styles.actions}>
        <Button title="닫기" variant="secondary" onPress={() => viewingStore.dismissSuggestion()} testID="suggestion-dismiss" />
        <Button title="볼게요" onPress={accept} testID="suggestion-accept" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    position: 'absolute',
    left: spacing.md,
    right: spacing.md,
    zIndex: 10,
    elevation: 6,
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    padding: spacing.lg,
    gap: spacing.sm,
    shadowColor: '#000',
    shadowOpacity: 0.12,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
  },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.sm },
});
