// SCR-01 11번 동네 인기글 (07 SCR-01 빈 상태: 0건이면 "아직 이 동네 글이 없어요" + 글쓰기(현재 동네일 때만), 가짜 카드 없음)
// 고르는 기준은 community(BR-COM-13)이고 앱은 받은 순서 그대로 home.popular-posts-count장까지 보여준다.
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { AppText, Button, SectionState } from '@/components/ui';
import { PostCard } from '@/community/components/PostParts';
import { FEED_EMPTY } from '@/community/FeedScreen';
import { useLikeAction } from '@/community/likes';
import { getAppParams } from '@/config/appParams';
import type { ViewingKind } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { useRestoredAction } from '@/pending/pendingAction';
import { spacing } from '@/theme';

import { uiStatus } from '../hourly';
import type { HomeView } from '../types';

import { Card } from './WeatherSections';

export function PopularPostsSection({
  home,
  viewingKind,
  onRetry,
}: {
  home: HomeView;
  viewingKind: ViewingKind;
  onRetry: () => void;
}) {
  const likes = useLikeAction('/');
  useRestoredAction('like', ({ notice }) => likes.setNotice(notice));
  const section = home.popularPosts;
  const posts = (section.data ?? []).slice(0, getAppParams().homePopularPostsCount);
  const status = uiStatus(section) === 'failed' ? 'failed' : posts.length === 0 ? 'empty' : 'ok';
  const isCurrent = viewingKind === 'current';

  return (
    <Card title="동네 인기글" testID="section-popular-posts">
      <SectionState
        status={status}
        emptyMessage={FEED_EMPTY}
        emptyAction={
          isCurrent ? (
            <Button title="글쓰기" onPress={() => router.push(routes.postEditor())} testID="popular-write" />
          ) : null
        }
        onRetry={onRetry}
      >
        <View style={styles.list}>
          {posts.map((post) => (
            <PostCard
              key={post.postId}
              post={post}
              onOpen={() => router.push(routes.postDetail(post.postId))}
              onLike={() => likes.toggle(post)}
              likePending={likes.isPending(post.postId)}
            />
          ))}
          {likes.notice ? (
            <AppText tone="danger" accessibilityRole="alert">
              {likes.notice}
            </AppText>
          ) : null}
          <Button
            title="더 보기"
            variant="secondary"
            onPress={() => router.navigate(routes.community())}
            testID="popular-more"
          />
        </View>
      </SectionState>
    </Card>
  );
}

const styles = StyleSheet.create({
  list: { gap: spacing.sm },
});
