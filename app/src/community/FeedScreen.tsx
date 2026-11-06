// SCR-06 커뮤니티 피드 (07 SCR-06, §4 규칙 2 보는 동네 공유, 05 FR-COM-05·BR-COM-02·08)
// - 보는 동네의 글을 최신순으로 페이지 단위로 이어 불러온다. 보는 동네가 바뀌면 그 동네 피드로 바뀐다.
// - 글쓰기는 보는 동네가 현재 동네일 때만(ADR 0004). 좋아요는 동네 제한 없음.
// - 화면 진입 때 무효화된 피드면 다시 부른다. 글 작성 직후에는 무효화로 새 글이 맨 위에 온다.
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { router, useFocusEffect } from 'expo-router';
import { type ReactElement, useCallback, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { api } from '@/api';
import { AppText, Button, SectionState } from '@/components/ui';
import { getAppParams } from '@/config/appParams';
import type { HomeView } from '@/home/types';
import { useNeighborhoodDetail } from '@/neighborhood/hooks';
import { useViewingState } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { useRestoredAction } from '@/pending/pendingAction';
import { useWideLayout } from '@/platform/wideLayout';
import { queryKeys } from '@/query/queryKeys';
import { colors, radii, spacing } from '@/theme';

import { fetchFeed } from './api';
import { PostCard } from './components/PostParts';
import { PostDetailScreen } from './PostDetailScreen';
import { useLikeAction } from './likes';
import type { PostPage } from './types';

export const FEED_ROUTE = '/community';
export const WRITE_ONLY_CURRENT = '글은 현재 동네에서만 쓸 수 있어요';
export const FEED_EMPTY = '아직 이 동네 글이 없어요';

/** 보는 동네가 현재 동네인가: 기기 판정 또는 회원 홈 응답의 현재 동네 표시 */
export function useIsViewingCurrent(viewingId: string | null): boolean {
  const viewing = useViewingState();
  const queryClient = useQueryClient();
  if (!viewingId) {
    return false;
  }
  if (viewing.current?.neighborhoodId === viewingId) {
    return true;
  }
  const home = queryClient.getQueryData<HomeView>(queryKeys.home.byNeighborhood(viewingId));
  return home?.neighborhood.id === viewingId && home.neighborhood.current === true;
}

export function useFeed(neighborhoodId: string | null) {
  return useInfiniteQuery({
    queryKey: queryKeys.community.feed(neighborhoodId ?? ''),
    enabled: neighborhoodId !== null,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => fetchFeed(api, neighborhoodId!, pageParam, getAppParams().feedPageSize, signal),
    getNextPageParam: (last: PostPage) => last.nextCursor ?? undefined,
    // 변경 후 무효화로만 다시 부른다(07 §5). 탭 전환마다 다시 부르지 않는다.
    staleTime: Infinity,
  });
}

export function FeedScreen() {
  const viewing = useViewingState();
  const queryClient = useQueryClient();
  const viewingId = viewing.viewingNeighborhoodId;
  const name = useNeighborhoodDetail(viewingId);
  const isCurrent = useIsViewingCurrent(viewingId);
  const feed = useFeed(viewingId);
  const likes = useLikeAction(FEED_ROUTE);
  const [pulling, setPulling] = useState(false);
  const wide = useWideLayout();
  // 넓은 화면: 목록 옆에 고른 글을 연다. 고르기 전에는 맨 위 글.
  const [selected, setSelected] = useState<string | null>(null);

  useRestoredAction('like', ({ notice }) => likes.setNotice(notice));

  useFocusEffect(
    useCallback(() => {
      if (!viewingId) {
        return;
      }
      const state = queryClient.getQueryState(queryKeys.community.feed(viewingId));
      if (state?.isInvalidated) {
        queryClient.refetchQueries({ queryKey: queryKeys.community.feed(viewingId) }).catch(() => undefined);
      }
    }, [queryClient, viewingId]),
  );

  if (!viewing.hydrated) {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  if (!viewingId) {
    return (
      <SafeAreaView style={styles.center} edges={['left', 'right']}>
        <AppText variant="heading">동네를 골라 주세요</AppText>
        <Button title="동네 검색" onPress={() => router.push(routes.neighborhoodSearch())} />
      </SafeAreaView>
    );
  }

  const posts = feed.data?.pages.flatMap((page) => page.items) ?? [];
  const write = () => router.push(routes.postEditor());

  const refresh = () => {
    setPulling(true);
    feed.refetch().finally(() => setPulling(false));
  };

  const header = (
    <View style={styles.header}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="동네 바꾸기"
        onPress={() => router.push(routes.neighborhoodSwitch())}
        testID="feed-header"
      >
        <AppText variant="caption" tone="secondary">
          {isCurrent ? '📍 현재 동네' : '보는 동네'}
        </AppText>
        <AppText variant="title">{`${name.data?.eupmyeondongName ?? '동네'} ▾`}</AppText>
      </Pressable>
      <Button title="글쓰기" onPress={write} disabled={!isCurrent} testID="feed-write" />
      {!isCurrent ? (
        <AppText variant="caption" tone="secondary" testID="feed-write-disabled">
          {WRITE_ONLY_CURRENT}
        </AppText>
      ) : null}
      {likes.notice ? (
        <AppText tone="danger" accessibilityRole="alert" testID="feed-notice">
          {likes.notice}
        </AppText>
      ) : null}
    </View>
  );

  let body: ReactElement | null = null;
  if (feed.isPending) {
    body = <ActivityIndicator color={colors.primary} />;
  } else if (feed.isError && posts.length === 0) {
    body = <SectionState status="failed" failedMessage="글을 불러오지 못했어요" onRetry={() => feed.refetch()} />;
  } else if (posts.length === 0) {
    body = (
      <SectionState
        status="empty"
        emptyMessage={FEED_EMPTY}
        emptyAction={isCurrent ? <Button title="첫 글 쓰기" onPress={write} testID="feed-first-post" /> : null}
      />
    );
  }

  const shown = selected ?? posts[0]?.postId ?? null;
  const list = (
    <FlatList
      testID="feed-list"
      data={body ? [] : posts}
      keyExtractor={(item) => item.postId}
      contentContainerStyle={styles.content}
      ListHeaderComponent={
        <>
          {header}
          {body}
        </>
      }
      renderItem={({ item }) => (
        <View style={wide && item.postId === shown ? styles.selected : undefined}>
          <PostCard
            post={item}
            onOpen={() => (wide ? setSelected(item.postId) : router.push(routes.postDetail(item.postId)))}
            onLike={() => likes.toggle(item)}
            likePending={likes.isPending(item.postId)}
          />
        </View>
      )}
      ItemSeparatorComponent={() => <View style={styles.separator} />}
      onEndReachedThreshold={0.5}
      onEndReached={() => {
        if (feed.hasNextPage && !feed.isFetchingNextPage && !feed.isError) {
          feed.fetchNextPage().catch(() => undefined);
        }
      }}
      ListFooterComponent={
        feed.isFetchingNextPage ? (
          <ActivityIndicator color={colors.primary} />
        ) : feed.isError && posts.length > 0 ? (
          <SectionState
            status="failed"
            failedMessage="다음 글을 불러오지 못했어요"
            onRetry={() => feed.fetchNextPage()}
          />
        ) : null
      }
      refreshControl={<RefreshControl refreshing={pulling} onRefresh={refresh} />}
    />
  );

  return (
    <SafeAreaView style={styles.root} edges={['left', 'right']}>
      {wide ? (
        <View style={styles.split}>
          <View style={styles.listPane}>{list}</View>
          <View style={styles.detailPane} testID="feed-detail-pane">
            {shown ? (
              <PostDetailScreen key={shown} postId={shown} onGone={() => setSelected(null)} />
            ) : (
              <AppText tone="secondary" style={styles.detailEmpty}>
                글을 고르면 여기에 보여요
              </AppText>
            )}
          </View>
        </View>
      ) : (
        list
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  loading: { marginTop: spacing.xl },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.md,
    padding: spacing.xl,
    backgroundColor: colors.background,
  },
  content: { padding: spacing.lg, gap: spacing.md },
  header: { gap: spacing.sm, marginBottom: spacing.md, borderRadius: radii.md },
  separator: { height: spacing.md },
  split: { flex: 1, flexDirection: 'row' },
  listPane: { width: 440 },
  detailPane: { flex: 1, borderLeftWidth: 1, borderLeftColor: colors.border },
  detailEmpty: { marginTop: spacing.xxl, textAlign: 'center' },
  selected: { borderRadius: radii.lg, borderWidth: 2, borderColor: colors.primary },
});
