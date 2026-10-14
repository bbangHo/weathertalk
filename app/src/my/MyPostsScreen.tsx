// SCR-13 내가 쓴 글 (07 SCR-13, 05 FR-COM-16): 모든 동네의 내 글을 최신순으로 이어 불러온다. 카드 → SCR-07(수정·삭제는 상세에서).
import { useInfiniteQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import { api } from '@/api';
import { AppText, Button, SectionState } from '@/components/ui';
import { fetchMyPosts } from '@/community/api';
import type { PostPage } from '@/community/types';
import { getAppParams } from '@/config/appParams';
import { routes } from '@/navigation/routes';
import { writeGate } from '@/pending/writeGate';
import { queryKeys } from '@/query/queryKeys';
import { useSession } from '@/session/useSession';
import { colors, spacing } from '@/theme';

import { MyPostRow } from './components';

export const MY_POSTS_EMPTY = '아직 쓴 글이 없어요';

export function MyPostsScreen() {
  const session = useSession();
  const isMember = session.status === 'member';
  const posts = useInfiniteQuery({
    queryKey: queryKeys.me.posts(),
    enabled: isMember,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => fetchMyPosts(api, pageParam, getAppParams().feedPageSize),
    getNextPageParam: (last: PostPage) => last.nextCursor ?? undefined,
  });

  if (!isMember) {
    return <MembersOnly />;
  }
  if (posts.isPending) {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  const items = posts.data?.pages.flatMap((page) => page.items) ?? [];
  if (posts.isError && items.length === 0) {
    return (
      <View style={styles.padded}>
        <SectionState status="failed" failedMessage="글을 불러오지 못했어요" onRetry={() => posts.refetch()} />
      </View>
    );
  }
  if (items.length === 0) {
    return (
      <View style={styles.padded}>
        <SectionState status="empty" emptyMessage={MY_POSTS_EMPTY} />
      </View>
    );
  }
  return (
    <FlatList
      testID="my-posts-list"
      style={styles.root}
      contentContainerStyle={styles.content}
      data={items}
      keyExtractor={(item) => item.postId}
      renderItem={({ item }) => <MyPostRow post={item} onOpen={() => router.push(routes.postDetail(item.postId))} />}
      onEndReachedThreshold={0.5}
      onEndReached={() => {
        if (posts.hasNextPage && !posts.isFetchingNextPage && !posts.isError) {
          posts.fetchNextPage().catch(() => undefined);
        }
      }}
      refreshControl={<RefreshControl refreshing={posts.isRefetching && !posts.isFetchingNextPage} onRefresh={() => posts.refetch()} />}
      ListFooterComponent={
        posts.isFetchingNextPage ? (
          <ActivityIndicator color={colors.primary} />
        ) : posts.isError ? (
          <SectionState status="failed" failedMessage="다음 글을 불러오지 못했어요" onRetry={() => posts.fetchNextPage()} />
        ) : null
      }
    />
  );
}

export function MembersOnly() {
  return (
    <View style={styles.padded}>
      <AppText tone="secondary">가입한 회원만 볼 수 있어요</AppText>
      <Button title="가입하기" onPress={() => writeGate.openSignup()} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  loading: { marginTop: spacing.xl },
  padded: { padding: spacing.lg, gap: spacing.md },
  content: { padding: spacing.lg },
});
