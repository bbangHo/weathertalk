// SCR-01 홈 (07 SCR-01, §1.4-4, §2). 첫 실행이면 첫 실행 안내로, 보는 동네가 없으면 "동네를 골라 주세요".
import { Redirect, router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ErrorCodes, isApiError } from '@/api/errors';
import { AppText, Button } from '@/components/ui';
import { useInterestNeighborhoods } from '@/neighborhood/hooks';
import { useViewingState, viewingKindOf, viewingStore } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { useSession } from '@/session/useSession';
import { colors, spacing } from '@/theme';

import { HomeContent } from './components/HomeContent';
import { useHome } from './useHome';

export function HomeScreen() {
  const viewing = useViewingState();
  const session = useSession();
  if (!viewing.hydrated || session.status === 'restoring') {
    return <Loading />;
  }
  if (!viewing.onboardingCompleted) {
    return <Redirect href={routes.welcome()} />;
  }
  return <HomeBody />;
}

function Loading() {
  return (
    <View style={styles.center}>
      <ActivityIndicator color={colors.primary} />
    </View>
  );
}

function HomeBody() {
  const viewing = useViewingState();
  const session = useSession();
  const { query, enabled, home, cachedSavedAt } = useHome();
  const interests = useInterestNeighborhoods();
  const [pulling, setPulling] = useState(false);
  const handledErrorAt = useRef(0);

  // 보는 동네를 정할 수 없으면(동네 ID 없음·없는 동네) 동네 검색으로 보낸다(07 §1.4-4). 같은 오류로 두 번 보내지 않는다.
  useEffect(() => {
    const error = query.error;
    if (!isApiError(error, ErrorCodes.VIEWING_NEIGHBORHOOD_UNRESOLVED) || handledErrorAt.current === query.errorUpdatedAt) {
      return;
    }
    handledErrorAt.current = query.errorUpdatedAt;
    if (viewing.viewingNeighborhoodId !== null) {
      viewingStore.setViewing(null).catch(() => undefined);
    }
    router.push(routes.neighborhoodSearch());
  }, [query.error, query.errorUpdatedAt, viewing.viewingNeighborhoodId]);

  const refresh = () => {
    setPulling(true);
    query.refetch().finally(() => setPulling(false));
  };

  if (!enabled) {
    return <PickNeighborhood />;
  }
  if (!home) {
    if (query.isError && !isApiError(query.error, ErrorCodes.VIEWING_NEIGHBORHOOD_UNRESOLVED)) {
      return (
        <SafeAreaView style={styles.center} edges={['top']}>
          <AppText tone="danger">날씨를 불러오지 못했어요</AppText>
          <Button title="다시 시도" variant="secondary" onPress={() => query.refetch()} />
        </SafeAreaView>
      );
    }
    if (query.isError) {
      return <PickNeighborhood />;
    }
    return <Loading />;
  }

  const kind = viewingKindOf({
    viewingNeighborhoodId: home.neighborhood.id,
    currentNeighborhoodId: viewing.current?.neighborhoodId ?? null,
    serverSaysCurrent: home.neighborhood.current,
    interestIds: (interests.query.data ?? []).map((item) => item.id),
  });

  return (
    <SafeAreaView style={styles.root} edges={['top']}>
      <HomeContent
        home={home}
        viewingKind={kind}
        cachedSavedAt={cachedSavedAt}
        refreshFailed={cachedSavedAt !== null && query.isError}
        refreshing={pulling}
        onRefresh={refresh}
        onOpenSwitch={() => router.push(routes.neighborhoodSwitch())}
        isMember={session.status === 'member'}
      />
    </SafeAreaView>
  );
}

/** 보는 동네 없음(위치 거부 + 검색 안 함) — 07 SCR-01 빈 상태 */
function PickNeighborhood() {
  return (
    <SafeAreaView style={styles.center} edges={['top']}>
      <AppText variant="heading">동네를 골라 주세요</AppText>
      <AppText tone="secondary">날씨를 볼 동네를 이름으로 찾을 수 있어요.</AppText>
      <Button title="동네 검색" onPress={() => router.push(routes.neighborhoodSearch())} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.md,
    padding: spacing.xl,
    backgroundColor: colors.background,
  },
});
