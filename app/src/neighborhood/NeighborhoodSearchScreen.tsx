// SCR-03 동네 검색 (07 SCR-03, §2 규칙 4, 02 FR-NBR-07). 위치 거부 시 대체 경로.
import { useQuery } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { api } from '@/api';
import { AppText, Button, SectionState } from '@/components/ui';
import { getAppParams } from '@/config/appParams';
import { routes } from '@/navigation/routes';
import { queryKeys } from '@/query/queryKeys';
import { colors, radii, spacing } from '@/theme';

import { searchNeighborhoods } from './api';
import { useInterestNeighborhoods } from './hooks';
import type { NeighborhoodSummary } from './types';
import { viewingStore } from './viewingStore';

export const SEARCH_DEBOUNCE_MS = 300;

function useDebounced(value: string, delayMs: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/** 모달을 닫고 원래 탭으로. 모달이 아닌 경로(첫 실행 등)로 들어왔으면 홈으로 바꾼다. */
export function closeToTabs() {
  if (router.canDismiss()) {
    router.dismissAll();
  } else {
    router.replace(routes.home());
  }
}

export function NeighborhoodSearchScreen() {
  const params = useLocalSearchParams<{ fromOnboarding?: string }>();
  const fromOnboarding = params.fromOnboarding === '1';
  const [text, setText] = useState('');
  const keyword = useDebounced(text.trim(), SEARCH_DEBOUNCE_MS);
  const minLength = getAppParams().neighborhoodSearchMinLength;
  const searchable = keyword.length >= minLength;

  const search = useQuery({
    queryKey: queryKeys.neighborhood.search(keyword),
    queryFn: ({ signal }) => searchNeighborhoods(api, keyword, signal),
    enabled: searchable,
    staleTime: 5 * 60 * 1000,
  });

  const [selected, setSelected] = useState<NeighborhoodSummary | null>(null);
  // 첫 실행에서 고른 동네: 이미 보는 동네로 정했고 관심 동네 추가만 묻는 중
  const [onboardingPick, setOnboardingPick] = useState<NeighborhoodSummary | null>(null);

  const select = async (item: NeighborhoodSummary) => {
    if (fromOnboarding) {
      await viewingStore.setViewing(item.id);
      setOnboardingPick(item);
      return;
    }
    setSelected((prev) => (prev?.id === item.id ? null : item));
  };

  if (onboardingPick) {
    return <OnboardingAddPrompt neighborhood={onboardingPick} />;
  }

  return (
    <SafeAreaView style={styles.root} edges={['left', 'right', 'bottom']}>
      <TextInput
        style={styles.input}
        value={text}
        onChangeText={setText}
        placeholder="동네 이름 (예: 역삼1동)"
        placeholderTextColor={colors.textDisabled}
        autoFocus
        autoCorrect={false}
        returnKeyType="search"
        accessibilityLabel="동네 이름 검색"
        testID="neighborhood-search-input"
      />
      {fromOnboarding ? (
        <AppText variant="caption" tone="secondary">
          고른 동네의 날씨를 볼 수 있어요. 체감 기록·글쓰기는 위치로 확인한 현재 동네에서만 할 수 있어요.
        </AppText>
      ) : null}
      {!searchable ? (
        <AppText tone="secondary">읍면동 이름으로 검색해 보세요</AppText>
      ) : search.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : search.isError ? (
        <SectionState status="failed" failedMessage="검색하지 못했어요" onRetry={() => search.refetch()} />
      ) : search.data.length === 0 ? (
        <SectionState status="empty" emptyMessage="찾는 동네가 없어요. 읍면동 이름으로 검색해 보세요" />
      ) : (
        <FlatList
          data={search.data}
          keyExtractor={(item) => item.id}
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={styles.list}
          renderItem={({ item }) => (
            <View style={styles.result}>
              <Pressable accessibilityRole="button" onPress={() => select(item)} testID={`search-result-${item.id}`}>
                <AppText variant="button">{item.eupmyeondongName}</AppText>
                <AppText variant="caption" tone="secondary">
                  {`${item.sidoName} ${item.sigunguName} ${item.eupmyeondongName}`}
                </AppText>
              </Pressable>
              {selected?.id === item.id ? <ResultActions neighborhood={item} /> : null}
            </View>
          )}
        />
      )}
    </SafeAreaView>
  );
}

function ResultActions({ neighborhood }: { neighborhood: NeighborhoodSummary }) {
  const interests = useInterestNeighborhoods();
  const [message, setMessage] = useState<string | null>(null);
  const list = interests.query.data ?? [];
  const already = list.some((item) => item.id === neighborhood.id);
  const full = !already && list.length >= interests.max;

  const view = async () => {
    await viewingStore.setViewing(neighborhood.id);
    closeToTabs();
  };
  const add = async () => {
    const result = await interests.actions.add(neighborhood);
    setMessage(result.ok ? '관심 동네에 추가했어요' : result.message);
  };

  return (
    <View style={styles.actions}>
      <Button title="이 동네 보기" onPress={view} testID="search-view" />
      <Button
        title={already ? '이미 관심 동네예요' : '관심 동네로 추가'}
        variant="secondary"
        onPress={add}
        disabled={already || full || interests.query.isPending}
        testID="search-add-interest"
      />
      {full ? (
        <AppText variant="caption" tone="secondary">{`관심 동네는 ${interests.max}개까지 추가할 수 있어요.`}</AppText>
      ) : null}
      {message ? <AppText variant="caption">{message}</AppText> : null}
    </View>
  );
}

/** 첫 실행에서 동네를 고른 직후: 보는 동네로 정했고, 관심 동네로 추가할지 묻는다(07 SCR-03, §2 규칙 4). */
function OnboardingAddPrompt({ neighborhood }: { neighborhood: NeighborhoodSummary }) {
  const interests = useInterestNeighborhoods();
  const [message, setMessage] = useState<string | null>(null);
  const goHome = () => router.replace(routes.home());
  const addAndGo = async () => {
    const result = await interests.actions.add(neighborhood);
    if (result.ok) {
      goHome();
    } else {
      setMessage(result.message);
    }
  };
  return (
    <SafeAreaView style={[styles.root, styles.prompt]} testID="onboarding-add-prompt">
      <AppText variant="heading">{`${neighborhood.eupmyeondongName} 날씨를 보여드릴게요`}</AppText>
      <AppText tone="secondary">관심 동네로 추가하면 동네 전환에서 바로 고를 수 있어요.</AppText>
      {message ? <AppText tone="danger">{message}</AppText> : null}
      <Button title="관심 동네로 추가" onPress={addAndGo} testID="onboarding-add-interest" />
      <Button title="추가하지 않고 보기" variant="secondary" onPress={goHome} testID="onboarding-skip-interest" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background, padding: spacing.lg, gap: spacing.md },
  prompt: { justifyContent: 'center' },
  input: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radii.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    fontSize: 16,
    color: colors.textPrimary,
  },
  list: { gap: spacing.sm },
  result: {
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
    gap: spacing.sm,
  },
  actions: { gap: spacing.sm },
});
