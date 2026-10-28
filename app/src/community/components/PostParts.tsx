// 게시글 표시 조각 (07 SCR-06 카드 항목, SCR-07, SCR-13). 이미지 캐시 키는 imageId(사전 서명 주소는 조회마다 바뀜, owner-todos §6).
import { Image } from 'expo-image';
import { Pressable, StyleSheet, View } from 'react-native';

import { AppText } from '@/components/ui';
import { createTagLabeler, useFeelingTags } from '@/feeling/labels';
import { colors, radii, spacing } from '@/theme';

import { kstDateTimeText, relativeTimeText, sensitivityText } from '../format';
import type { AttachedFeelingRecord, PostImage, PostSummary } from '../types';

export function PostImageView({
  image,
  size,
  onPress,
  testID,
}: {
  image: PostImage;
  size: number | 'full';
  onPress?: () => void;
  testID?: string;
}) {
  const style =
    size === 'full'
      ? { width: '100%' as const, aspectRatio: image.width && image.height ? image.width / image.height : 1 }
      : { width: size, height: size };
  const content = image.url ? (
    <Image
      source={{ uri: image.url, cacheKey: image.imageId }}
      recyclingKey={image.imageId}
      style={[styles.image, style]}
      contentFit={size === 'full' ? 'contain' : 'cover'}
      accessibilityLabel="게시글 사진"
    />
  ) : (
    <View style={[styles.image, styles.noImage, style]}>
      <AppText variant="caption" tone="secondary">
        사진을 불러올 수 없어요
      </AppText>
    </View>
  );
  if (!onPress) {
    return <View testID={testID}>{content}</View>;
  }
  return (
    <Pressable accessibilityRole="imagebutton" accessibilityLabel="사진 크게 보기" onPress={onPress} testID={testID}>
      {content}
    </Pressable>
  );
}

export function AttachedFeelingView({ record }: { record: AttachedFeelingRecord }) {
  const tags = useFeelingTags();
  const label = createTagLabeler(tags.data);
  const parts = [label(record.temperature), label(record.humidity), label(record.wind)].filter(Boolean);
  return (
    <View style={styles.feeling} testID="attached-feeling">
      <AppText variant="caption" tone="secondary">
        {`체감 기록 · ${sensitivityText(record.temperatureSensitivity)} · ${kstDateTimeText(record.recordedAt)}`}
      </AppText>
      <AppText>{parts.join(' · ')}</AppText>
    </View>
  );
}

export function LikeButton({
  post,
  pending,
  onPress,
}: {
  post: PostSummary;
  pending: boolean;
  onPress: () => void;
}) {
  const liked = post.likedByViewer === true;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={liked ? '좋아요 취소' : '좋아요'}
      accessibilityState={{ selected: liked, disabled: pending || post.mine === true }}
      disabled={pending}
      onPress={onPress}
      hitSlop={8}
      style={styles.like}
      testID={`like-${post.postId}`}
    >
      <AppText tone={liked ? 'danger' : 'secondary'}>{liked ? '♥' : '♡'}</AppText>
      <AppText variant="caption" tone="secondary" testID={`like-count-${post.postId}`}>
        {String(post.likeCount)}
      </AppText>
    </Pressable>
  );
}

export function AuthorLine({ post }: { post: PostSummary }) {
  return (
    <View style={styles.authorLine}>
      <AppText variant="button">{post.author.nickname}</AppText>
      <AppText variant="caption" tone="secondary">
        {sensitivityText(post.author.temperatureSensitivity)}
      </AppText>
      <AppText variant="caption" tone="secondary">
        {`· ${relativeTimeText(post.createdAt)}${post.edited ? ' · 수정됨' : ''}`}
      </AppText>
    </View>
  );
}

/** 피드·홈 인기글 카드: 본문 미리보기, 이미지 미리보기, 첨부 체감 기록, 좋아요·댓글 수 */
export function PostCard({
  post,
  onOpen,
  onLike,
  likePending,
  showNeighborhood = false,
}: {
  post: PostSummary;
  onOpen: () => void;
  onLike?: () => void;
  likePending?: boolean;
  showNeighborhood?: boolean;
}) {
  return (
    <View style={styles.card} testID={`post-card-${post.postId}`}>
      <Pressable accessibilityRole="button" onPress={onOpen} style={styles.cardBody} testID={`open-post-${post.postId}`}>
        {showNeighborhood ? (
          <AppText variant="caption" tone="secondary">
            {post.neighborhood.shortName}
          </AppText>
        ) : null}
        <AuthorLine post={post} />
        <AppText numberOfLines={3}>{post.body}</AppText>
        {post.images.length > 0 ? (
          <View style={styles.thumbs}>
            {post.images.slice(0, 3).map((image) => (
              <PostImageView key={image.imageId} image={image} size={72} />
            ))}
            {post.images.length > 3 ? (
              <AppText variant="caption" tone="secondary">{`+${post.images.length - 3}`}</AppText>
            ) : null}
          </View>
        ) : null}
        {post.attachedFeelingRecord ? <AttachedFeelingView record={post.attachedFeelingRecord} /> : null}
      </Pressable>
      <View style={styles.counts}>
        {onLike ? (
          <LikeButton post={post} pending={Boolean(likePending)} onPress={onLike} />
        ) : (
          <AppText variant="caption" tone="secondary">{`♡ ${post.likeCount}`}</AppText>
        )}
        <AppText variant="caption" tone="secondary" testID={`comment-count-${post.postId}`}>
          {`댓글 ${post.commentCount}`}
        </AppText>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.lg,
    gap: spacing.sm,
  },
  cardBody: { gap: spacing.sm },
  authorLine: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.xs },
  thumbs: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  image: { borderRadius: radii.sm, backgroundColor: colors.disabledBackground },
  noImage: { alignItems: 'center', justifyContent: 'center', padding: spacing.xs },
  feeling: {
    backgroundColor: colors.background,
    borderRadius: radii.md,
    padding: spacing.sm,
    gap: 2,
  },
  counts: { flexDirection: 'row', alignItems: 'center', gap: spacing.lg },
  like: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
});
