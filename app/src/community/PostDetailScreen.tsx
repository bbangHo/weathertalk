// SCR-07 게시글 상세 (07 SCR-07, §3.1 댓글·답글·좋아요, §7.3 댓글 알림 → 댓글 영역, 05 FR-COM-06~11)
// - 댓글·답글은 1단계: 답글에는 답글 버튼이 없다. 답글이 남은 지운 댓글은 "삭제된 댓글" 자리로 보인다.
// - 회원은 글의 동네와 관계없이 댓글·답글·좋아요를 할 수 있고 위치 판정이 필요 없다(BR-COM-02).
// - 비회원이 댓글을 쓰면 입력을 보류 행동으로 남기고 가입 시트를 연다(입력창은 그대로).
// - 없는(삭제된) 글이면 "삭제된 글이에요"를 보여주고 이전 화면으로 돌아간다.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { api } from '@/api';
import { ErrorCodes, isApiError } from '@/api/errors';
import { useIdempotencyKey } from '@/api/idempotency';
import { AppText, Button, SectionState } from '@/components/ui';
import { getAppParams } from '@/config/appParams';
import { routes } from '@/navigation/routes';
import { useRestoredAction } from '@/pending/pendingAction';
import { writeGate } from '@/pending/writeGate';
import { Alert } from '@/platform/alert';
import { useWideLayout } from '@/platform/wideLayout';
import { queryKeys } from '@/query/queryKeys';
import { colors, radii, spacing } from '@/theme';

import { createComment, createReply, deleteComment, deletePost, fetchComments, fetchPost } from './api';
import { AttachedFeelingView, AuthorLine, LikeButton, PostImageView } from './components/PostParts';
import { relativeTimeText, sensitivityText } from './format';
import { useLikeAction } from './likes';
import { invalidatePostLists } from './postCache';
import type { CommentView, PostImage } from './types';

export const POST_DELETED = '삭제된 글이에요';
export const DELETED_COMMENT = '삭제된 댓글';
export const COMMENT_FAILED = '댓글을 남기지 못했어요. 다시 시도해 주세요.';
/** 삭제 안내를 읽을 시간을 두고 돌아간다 */
export const DELETED_BACK_DELAY_MS = 1200;

export function postDetailRoute(postId: string): string {
  return `/community/${postId}`;
}

function goBack() {
  if (router.canGoBack()) {
    router.back();
  } else {
    router.replace(routes.community());
  }
}

function errorText(error: unknown, fallback: string): string {
  return isApiError(error) && error.detail ? error.detail : fallback;
}

/** onGone: 지워진 글을 떠날 때. 넓은 화면 커뮤니티 분할에서는 이전 화면으로 가지 않고 선택만 푼다. */
export function PostDetailScreen({
  postId,
  focus,
  onGone = goBack,
}: {
  postId: string;
  focus?: 'comments';
  onGone?: () => void;
}) {
  const wide = useWideLayout();
  const queryClient = useQueryClient();
  const route = postDetailRoute(postId);
  const detail = useQuery({
    queryKey: queryKeys.community.post(postId),
    queryFn: ({ signal }) => fetchPost(api, postId, signal),
  });
  const likes = useLikeAction(route);
  // 이어 붙인 댓글 페이지. 상세를 새로 불러오면(base가 달라지면) 버린다 — 첫 페이지가 새로 왔으므로.
  const [more, setMore] = useState<{ base: number; items: CommentView[]; nextCursor: string | null } | null>(null);
  const [moreError, setMoreError] = useState(false);
  const [viewer, setViewer] = useState<PostImage | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [pulling, setPulling] = useState(false);
  const scrollRef = useRef<ScrollView>(null);
  const commentsY = useRef<number | null>(null);
  const scrolled = useRef(false);
  const composerRef = useRef<ComposerHandle>(null);

  useRestoredAction('like', ({ notice }) => likes.setNotice(notice));

  const notFound = isApiError(detail.error, ErrorCodes.NOT_FOUND);
  useEffect(() => {
    if (!notFound) {
      return;
    }
    invalidatePostLists(queryClient).catch(() => undefined);
    const timer = setTimeout(onGone, DELETED_BACK_DELAY_MS);
    return () => clearTimeout(timer);
  }, [notFound, queryClient, onGone]);

  const tryScrollToComments = () => {
    if (focus === 'comments' && !scrolled.current && commentsY.current !== null) {
      scrolled.current = true;
      scrollRef.current?.scrollTo({ y: commentsY.current, animated: true });
    }
  };

  if (notFound) {
    return (
      <SafeAreaView style={styles.center} edges={['left', 'right']} testID="post-deleted">
        <AppText variant="heading">{POST_DELETED}</AppText>
        <Button title="돌아가기" variant="secondary" onPress={onGone} />
      </SafeAreaView>
    );
  }
  if (detail.isPending) {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  if (detail.isError || !detail.data) {
    return (
      <View style={styles.padded}>
        <SectionState status="failed" failedMessage="글을 불러오지 못했어요" onRetry={() => detail.refetch()} />
      </View>
    );
  }

  const { post, comments } = detail.data;
  const extra = more && more.base === detail.dataUpdatedAt ? more : null;
  const allComments = [...comments.items, ...(extra?.items ?? [])];
  const nextCursor = extra ? extra.nextCursor : comments.nextCursor;

  const loadMore = async () => {
    if (!nextCursor) {
      return;
    }
    setMoreError(false);
    try {
      const page = await fetchComments(api, postId, nextCursor);
      const base = detail.dataUpdatedAt;
      setMore((prev) => ({
        base,
        items: [...(prev && prev.base === base ? prev.items : []), ...page.items],
        nextCursor: page.nextCursor,
      }));
    } catch {
      setMoreError(true);
    }
  };

  const refreshAfterChange = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.community.post(postId) }),
      invalidatePostLists(queryClient),
    ]);

  const confirmDeletePost = () =>
    Alert.alert('글을 지울까요?', '댓글과 좋아요도 함께 지워져요.', [
      { text: '취소', style: 'cancel' },
      {
        text: '삭제',
        style: 'destructive',
        onPress: async () => {
          setMessage(null);
          try {
            await deletePost(api, postId);
            queryClient.removeQueries({ queryKey: queryKeys.community.post(postId) });
            await invalidatePostLists(queryClient);
            onGone();
          } catch (error) {
            setMessage(errorText(error, '글을 지우지 못했어요. 다시 시도해 주세요.'));
          }
        },
      },
    ]);

  const confirmDeleteComment = (comment: CommentView) =>
    Alert.alert('댓글을 지울까요?', undefined, [
      { text: '취소', style: 'cancel' },
      {
        text: '삭제',
        style: 'destructive',
        onPress: async () => {
          setMessage(null);
          try {
            await deleteComment(api, comment.commentId);
            await refreshAfterChange();
          } catch (error) {
            setMessage(errorText(error, '댓글을 지우지 못했어요. 다시 시도해 주세요.'));
          }
        },
      },
    ]);

  return (
    <SafeAreaView style={[styles.root, wide && styles.rootWide]} edges={['left', 'right', 'bottom']}>
      <ScrollView
        ref={scrollRef}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={pulling}
            onRefresh={() => {
              setPulling(true);
              detail.refetch().finally(() => setPulling(false));
            }}
          />
        }
        testID="post-detail-scroll"
      >
        <AppText variant="caption" tone="secondary">
          {post.neighborhood.shortName}
        </AppText>
        <AuthorLine post={post} />
        <AppText testID="post-body">{post.body}</AppText>
        {post.images.map((image) => (
          <PostImageView
            key={image.imageId}
            image={image}
            size="full"
            onPress={() => setViewer(image)}
            testID={`post-image-${image.imageId}`}
          />
        ))}
        {post.attachedFeelingRecord ? <AttachedFeelingView record={post.attachedFeelingRecord} /> : null}
        <View style={styles.row}>
          <LikeButton post={post} pending={likes.isPending(post.postId)} onPress={() => likes.toggle(post)} />
          {post.mine ? (
            <View style={styles.row}>
              <Pressable
                accessibilityRole="button"
                onPress={() => router.push(routes.postEditor({ postId }))}
                testID="post-edit"
              >
                <AppText tone="secondary">수정</AppText>
              </Pressable>
              <Pressable accessibilityRole="button" onPress={confirmDeletePost} testID="post-delete">
                <AppText tone="danger">삭제</AppText>
              </Pressable>
            </View>
          ) : null}
        </View>
        {likes.notice || message ? (
          <AppText tone="danger" accessibilityRole="alert" testID="detail-notice">
            {likes.notice ?? message}
          </AppText>
        ) : null}

        <View
          style={styles.comments}
          onLayout={(event) => {
            commentsY.current = event.nativeEvent.layout.y;
            tryScrollToComments();
          }}
          testID="comments-section"
        >
          <AppText variant="heading">{`댓글 ${post.commentCount}`}</AppText>
          {allComments.length === 0 ? <AppText tone="secondary">첫 댓글을 남겨 보세요</AppText> : null}
          {allComments.map((comment) => (
            <CommentItem
              key={comment.commentId}
              comment={comment}
              isReply={false}
              onReply={() => composerRef.current?.replyTo(comment)}
              onDelete={confirmDeleteComment}
            />
          ))}
          {nextCursor ? (
            <Button title={moreError ? '다시 시도' : '댓글 더 보기'} variant="secondary" onPress={loadMore} />
          ) : null}
          {moreError ? <AppText tone="danger">댓글을 더 불러오지 못했어요</AppText> : null}
        </View>
      </ScrollView>
      <CommentComposer
        ref={composerRef}
        postId={postId}
        route={route}
        comments={allComments}
        onPosted={refreshAfterChange}
      />
      <Modal visible={viewer !== null} transparent animationType="fade" onRequestClose={() => setViewer(null)}>
        <Pressable
          style={styles.viewer}
          accessibilityLabel="사진 닫기"
          onPress={() => setViewer(null)}
          testID="image-viewer"
        >
          {viewer ? <PostImageView image={viewer} size="full" /> : null}
        </Pressable>
      </Modal>
    </SafeAreaView>
  );
}

function CommentItem({
  comment,
  isReply,
  onReply,
  onDelete,
}: {
  comment: CommentView;
  isReply: boolean;
  onReply: () => void;
  onDelete: (comment: CommentView) => void;
}) {
  return (
    <View style={[styles.comment, isReply && styles.reply]} testID={`comment-${comment.commentId}`}>
      {comment.deleted ? (
        <AppText tone="secondary">{DELETED_COMMENT}</AppText>
      ) : (
        <>
          <View style={styles.row}>
            <AppText variant="button">{comment.author?.nickname ?? ''}</AppText>
            <AppText variant="caption" tone="secondary">
              {`${sensitivityText(comment.author?.temperatureSensitivity)} · ${relativeTimeText(comment.createdAt)}${
                comment.edited ? ' · 수정됨' : ''
              }`}
            </AppText>
          </View>
          <AppText>{comment.body}</AppText>
          <View style={styles.row}>
            {/* 답글에는 답글 버튼을 두지 않는다(깊이 1, FR-COM-08). */}
            {!isReply ? (
              <Pressable accessibilityRole="button" onPress={onReply} testID={`reply-${comment.commentId}`}>
                <AppText variant="caption" tone="secondary">
                  답글
                </AppText>
              </Pressable>
            ) : null}
            {comment.mine ? (
              <Pressable
                accessibilityRole="button"
                onPress={() => onDelete(comment)}
                testID={`delete-comment-${comment.commentId}`}
              >
                <AppText variant="caption" tone="danger">
                  삭제
                </AppText>
              </Pressable>
            ) : null}
          </View>
        </>
      )}
      {comment.replies.map((reply) => (
        <CommentItem key={reply.commentId} comment={reply} isReply onReply={() => undefined} onDelete={onDelete} />
      ))}
    </View>
  );
}

type ComposerHandle = { replyTo: (comment: CommentView) => void };

const CommentComposer = forwardRef<
  ComposerHandle,
  { postId: string; route: string; comments: CommentView[]; onPosted: () => Promise<unknown> }
>(function CommentComposer({ postId, route, comments, onPosted }, ref) {
  const key = useIdempotencyKey();
  const attempted = useRef<string | null>(null);
  const [body, setBody] = useState('');
  const [parentId, setParentId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const max = getAppParams().commentMaxLength;

  useImperativeHandle(ref, () => ({
    replyTo: (comment) => {
      setParentId(comment.commentId);
      setError(null);
    },
  }));

  useRestoredAction('comment', ({ action }) => {
    if (action.payload.postId !== postId) {
      return;
    }
    setBody(action.payload.body);
    setParentId(action.payload.parentCommentId);
    setNotice('입력한 내용을 확인하고 남겨 주세요.');
  });

  const parent = parentId ? comments.find((comment) => comment.commentId === parentId) : null;
  const trimmed = body.trim();

  async function submit(): Promise<void> {
    if (!trimmed) {
      return;
    }
    const action = {
      kind: 'comment' as const,
      route,
      neighborhoodId: null,
      payload: { postId, parentCommentId: parentId, body },
    };
    if (!(await writeGate.requireMember(action))) {
      return;
    }
    // 같은 입력의 다시 시도는 같은 요청 식별자를 써서 서버가 한 번만 만들게 한다(BR-COM-12).
    const signature = JSON.stringify([parentId, trimmed]);
    if (attempted.current !== signature) {
      key.renew();
      attempted.current = signature;
    }
    setError(null);
    setNotice(null);
    try {
      if (parentId) {
        await createReply(api, parentId, trimmed, key.current());
      } else {
        await createComment(api, postId, trimmed, key.current());
      }
      attempted.current = null;
      key.renew();
      setBody('');
      setParentId(null);
      await onPosted().catch(() => undefined);
    } catch (cause) {
      if (await writeGate.handleWriteError(cause, action)) {
        return;
      }
      setError(errorText(cause, COMMENT_FAILED));
    }
  }

  return (
    <View style={styles.composer} testID="comment-composer">
      {parent ? (
        <View style={styles.row}>
          <AppText variant="caption" tone="secondary" testID="reply-target">
            {parent.deleted ? '삭제된 댓글에 답글' : `${parent.author?.nickname ?? ''}님에게 답글`}
          </AppText>
          <Pressable accessibilityRole="button" onPress={() => setParentId(null)} testID="reply-cancel">
            <AppText variant="caption" tone="primary">
              취소
            </AppText>
          </Pressable>
        </View>
      ) : null}
      {notice ? (
        <AppText variant="caption" tone="secondary">
          {notice}
        </AppText>
      ) : null}
      {error ? (
        <AppText variant="caption" tone="danger" accessibilityRole="alert" testID="comment-error">
          {error}
        </AppText>
      ) : null}
      <View style={styles.composerRow}>
        <TextInput
          style={styles.input}
          value={body}
          onChangeText={(text) => {
            setBody(text);
            setError(null);
          }}
          maxLength={max}
          multiline
          placeholder={parent ? '답글을 남겨 보세요' : '댓글을 남겨 보세요'}
          placeholderTextColor={colors.textDisabled}
          testID="comment-input"
        />
        <View style={styles.send}>
          <Button title={error ? '다시 시도' : '남기기'} onPress={submit} disabled={!trimmed} testID="comment-submit" />
        </View>
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  // 넓은 화면: 공유 링크로 글만 연 경우에도 읽기 좋은 폭의 가운데 열
  rootWide: { width: '100%', maxWidth: 720, alignSelf: 'center' },
  loading: { marginTop: spacing.xl },
  padded: { padding: spacing.lg },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.md,
    padding: spacing.xl,
    backgroundColor: colors.background,
  },
  content: { padding: spacing.lg, gap: spacing.md },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, flexWrap: 'wrap' },
  comments: { gap: spacing.md, marginTop: spacing.md },
  comment: {
    gap: spacing.xs,
    paddingVertical: spacing.sm,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  reply: { marginLeft: spacing.xl, borderTopWidth: 0 },
  composer: {
    borderTopWidth: 1,
    borderTopColor: colors.border,
    backgroundColor: colors.surface,
    padding: spacing.md,
    gap: spacing.xs,
  },
  composerRow: { flexDirection: 'row', alignItems: 'flex-end', gap: spacing.sm },
  input: {
    flex: 1,
    minHeight: 44,
    maxHeight: 120,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radii.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    color: colors.textPrimary,
  },
  send: { width: 96 },
  viewer: { flex: 1, backgroundColor: 'rgba(0,0,0,0.9)', justifyContent: 'center', padding: spacing.md },
});
