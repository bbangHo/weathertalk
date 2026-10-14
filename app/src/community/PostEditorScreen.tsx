// SCR-08 게시글 작성·수정 (07 SCR-08, §3.1 글쓰기 보류 행동, §6.1 작성 초안, 05 FR-COM-01~03·BR-COM-01·03·04·07)
// - 새 글은 현재 동네에만: 올리기 직전 좌표로 현재 동네를 다시 확인하고, 확인할 수 없으면 막는다.
// - 수정은 원래 동네 그대로이며 위치가 필요 없다. 체감 기록은 해제만 할 수 있다.
// - 본문 필수 1~max자(서버와 같은 값). 사진은 최대 max장, 모두 JPEG로 바꿔 올린다.
// - 비회원도 들어와 쓸 수 있고, 올릴 때 초안을 남기고 가입 시트를 연다. 닫을 때는 "초안 남기기"를 묻는다.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useNavigation } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, AppState, Image, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { api } from '@/api';
import { isApiError } from '@/api/errors';
import { useIdempotencyKey } from '@/api/idempotency';
import { AppText, Button, Screen, SectionState } from '@/components/ui';
import { getAppParams } from '@/config/appParams';
import { prepareWriteLocation } from '@/location/writeLocation';
import { useNeighborhoodDetail } from '@/neighborhood/hooks';
import { useViewingState, viewingStore } from '@/neighborhood/viewingStore';
import { routes } from '@/navigation/routes';
import { useRestoredAction } from '@/pending/pendingAction';
import { writeGate } from '@/pending/writeGate';
import { queryKeys } from '@/query/queryKeys';
import { colors, radii, spacing } from '@/theme';

import { createPost, fetchPost, updatePost } from './api';
import { AttachedFeelingView, PostImageView } from './components/PostParts';
import { isDraftEmpty, type PostDraft, postDraftStore } from './draft';
import { checkBody } from './format';
import { convertToJpeg, ImageUploadError, pickImages, uploadImage } from './imageUpload';
import { invalidatePostLists } from './postCache';
import type { AttachedFeelingRecord, PostSummary } from './types';

export const EDITOR_ROUTE = '/post-editor';
export const POST_LOCATION_UNAVAILABLE = '현재 동네를 확인할 수 없어 글을 올릴 수 없어요';
export const POST_FAILED = '글을 올리지 못했어요. 다시 시도해 주세요.';
export const DRAFT_RESTORED = '작성 중이던 초안을 불러왔어요';

type EditorImage = {
  key: string;
  localUri: string | null;
  imageId: string | null;
  url: string | null;
  error: string | null;
};

let imageSeq = 0;
const nextKey = () => `img-${++imageSeq}`;

function toEditorImages(draft: PostDraft['images']): EditorImage[] {
  return draft.map((image) => ({ key: nextKey(), localUri: image.localUri, imageId: image.imageId, url: null, error: null }));
}

function errorText(error: unknown, fallback: string): string {
  return isApiError(error) && error.detail ? error.detail : fallback;
}

export type PostEditorProps = { postId?: string; feelingRecordId?: string };

export function PostEditorScreen({ postId, feelingRecordId: paramRecordId }: PostEditorProps) {
  if (postId) {
    return <EditLoader postId={postId} />;
  }
  return <Editor mode={{ kind: 'new', paramRecordId: paramRecordId ?? null }} />;
}

function EditLoader({ postId }: { postId: string }) {
  const detail = useQuery({ queryKey: queryKeys.community.post(postId), queryFn: () => fetchPost(api, postId) });
  if (detail.isPending) {
    return <ActivityIndicator style={styles.loading} color={colors.primary} />;
  }
  if (detail.isError || !detail.data) {
    return (
      <Screen>
        <SectionState status="failed" failedMessage="글을 불러오지 못했어요" onRetry={() => detail.refetch()} />
      </Screen>
    );
  }
  return <Editor mode={{ kind: 'edit', post: detail.data.post }} />;
}

type Mode = { kind: 'new'; paramRecordId: string | null } | { kind: 'edit'; post: PostSummary };

function Editor({ mode }: { mode: Mode }) {
  const queryClient = useQueryClient();
  const navigation = useNavigation();
  const viewing = useViewingState();
  const key = useIdempotencyKey();
  const params = getAppParams();
  const isEdit = mode.kind === 'edit';
  const editPost = mode.kind === 'edit' ? mode.post : null;

  const [body, setBody] = useState(editPost?.body ?? '');
  const [images, setImages] = useState<EditorImage[]>(
    editPost
      ? editPost.images.map((image) => ({ key: nextKey(), localUri: null, imageId: image.imageId, url: image.url, error: null }))
      : [],
  );
  const [feelingRecordId, setFeelingRecordId] = useState<string | null>(
    mode.kind === 'new' ? mode.paramRecordId : null,
  );
  const [attached, setAttached] = useState<AttachedFeelingRecord | null>(editPost?.attachedFeelingRecord ?? null);
  const [detach, setDetach] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const leaving = useRef(false);
  const attempted = useRef<string | null>(null);

  const currentId = viewing.current?.neighborhoodId ?? null;
  const currentName = useNeighborhoodDetail(isEdit ? null : currentId);
  const check = checkBody(body, params.postMinLength, params.postMaxLength);

  const draft = (): PostDraft => ({
    body,
    images: images.map((image) => ({ localUri: image.localUri, imageId: image.imageId })),
    feelingRecordId,
  });
  const draftRef = useRef(draft);
  useEffect(() => {
    draftRef.current = draft;
  });

  const saveDraft = async (value: PostDraft = draftRef.current()) => {
    if (isEdit) {
      return;
    }
    await postDraftStore.save(value).catch(() => undefined);
  };

  // 새 글: 남겨 둔 초안을 불러온다. 체감 기록 화면에서 넘어온 기록이 있으면 그것을 붙인다.
  const paramRecordId = mode.kind === 'new' ? mode.paramRecordId : null;
  useEffect(() => {
    if (isEdit) {
      return;
    }
    let active = true;
    postDraftStore
      .load()
      .then((stored) => {
        if (!active || !stored || isDraftEmpty(stored)) {
          return;
        }
        setBody(stored.body);
        setImages(toEditorImages(stored.images));
        setFeelingRecordId(paramRecordId ?? stored.feelingRecordId);
        setNotice(DRAFT_RESTORED);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [isEdit, paramRecordId]);

  // 가입·재동의 뒤 복귀: 초안을 다시 읽어 채운다(자동 제출 안 함, §3 규칙 4).
  useRestoredAction('post', ({ action, notice: restoredNotice }) => {
    postDraftStore
      .load()
      .then((stored) => {
        if (stored) {
          setBody(stored.body);
          setImages(toEditorImages(stored.images));
        }
        setFeelingRecordId(action.payload.feelingRecordId ?? stored?.feelingRecordId ?? null);
        setNotice(restoredNotice ?? '작성한 내용을 확인하고 올려 주세요.');
      })
      .catch(() => undefined);
  });

  // 앱이 백그라운드로 가면 초안을 남긴다(업로드 중 이탈 포함).
  useEffect(() => {
    if (isEdit) {
      return;
    }
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active' && !isDraftEmpty(draftRef.current())) {
        saveDraft().catch(() => undefined);
      }
    });
    return () => subscription.remove();
    // saveDraft는 draftRef로 최신 값을 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isEdit]);

  // 닫기(뒤로 가기·제스처) 전에 초안을 남길지 묻는다.
  useEffect(() => {
    const unsubscribe = navigation.addListener('beforeRemove', (event) => {
      const current = draftRef.current();
      const dirty = isEdit
        ? current.body !== editPost?.body || current.images.length !== (editPost?.images.length ?? 0) || detach
        : !isDraftEmpty(current);
      if (leaving.current || !dirty) {
        return;
      }
      event.preventDefault();
      const proceed = () => {
        leaving.current = true;
        navigation.dispatch(event.data.action);
      };
      if (isEdit) {
        Alert.alert('수정을 그만둘까요?', '고친 내용은 저장되지 않아요.', [
          { text: '계속 고치기', style: 'cancel' },
          { text: '그만두기', style: 'destructive', onPress: proceed },
        ]);
        return;
      }
      Alert.alert('초안을 남길까요?', '다음에 글쓰기를 열면 이어서 쓸 수 있어요.', [
        { text: '계속 쓰기', style: 'cancel' },
        {
          text: '버리기',
          style: 'destructive',
          onPress: () => {
            postDraftStore.clear().catch(() => undefined);
            proceed();
          },
        },
        {
          text: '초안 남기기',
          onPress: () => {
            saveDraft(current).finally(proceed);
          },
        },
      ]);
    });
    return unsubscribe;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navigation, isEdit, detach]);

  const remaining = params.maxPostImages - images.length;

  async function addImages(): Promise<void> {
    setError(null);
    setAdding(true);
    try {
      const picked = await pickImages(remaining);
      const converted: EditorImage[] = [];
      for (const image of picked) {
        const jpeg = await convertToJpeg(image, params.imageMaxDimension);
        converted.push({ key: nextKey(), localUri: jpeg.uri, imageId: null, url: null, error: null });
      }
      if (converted.length > 0) {
        setImages((prev) => [...prev, ...converted].slice(0, params.maxPostImages));
      }
    } catch {
      setError('사진을 불러오지 못했어요. 다시 골라 주세요.');
    } finally {
      setAdding(false);
    }
  }

  /** 아직 안 올린 사진을 차례로 올린다. 실패한 장에 사유를 붙이고 멈춘다(그 장만 다시 올리면 된다). */
  async function uploadPending(list: EditorImage[]): Promise<EditorImage[] | null> {
    const result = [...list];
    for (let index = 0; index < result.length; index += 1) {
      const image = result[index];
      if (image.imageId || !image.localUri) {
        continue;
      }
      try {
        const uploaded = await uploadImage(api, image.localUri, params.imageMaxFileSize);
        result[index] = { ...image, imageId: uploaded.imageId, url: uploaded.url, error: null };
        setImages([...result]);
        // 올린 이미지 ID를 초안에 남겨 중간에 끊겨도 다시 올리지 않게 한다.
        await saveDraft({ body, images: result.map((i) => ({ localUri: i.localUri, imageId: i.imageId })), feelingRecordId });
      } catch (cause) {
        if (cause instanceof ImageUploadError) {
          result[index] = { ...image, error: cause.message };
          setImages([...result]);
          setError('사진을 올리지 못했어요. 표시된 사진을 빼거나 다시 시도해 주세요.');
          return null;
        }
        throw cause;
      }
    }
    return result;
  }

  async function submit(): Promise<void> {
    if (!check.valid) {
      return;
    }
    setError(null);
    setNotice(null);
    const action = {
      kind: 'post' as const,
      route: editPost ? `${EDITOR_ROUTE}?postId=${editPost.postId}` : EDITOR_ROUTE,
      neighborhoodId: null,
      payload: { feelingRecordId },
    };
    await saveDraft();
    if (!(await writeGate.requireMember(action))) {
      return;
    }
    setSubmitting(true);
    try {
      if (editPost) {
        const uploaded = await uploadPending(images);
        if (!uploaded) {
          return;
        }
        await updatePost(api, editPost.postId, {
          body: body.trim(),
          imageIds: uploaded.map((image) => image.imageId!),
          detachFeelingRecord: detach,
        });
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.community.post(editPost.postId) }),
          invalidatePostLists(queryClient),
        ]);
        leaving.current = true;
        router.back();
        return;
      }

      const where = await prepareWriteLocation({ api, store: viewingStore, isMember: true });
      if (where.status !== 'ok') {
        setError(POST_LOCATION_UNAVAILABLE);
        return;
      }
      const uploaded = await uploadPending(images);
      if (!uploaded) {
        return;
      }
      const input = { body: body.trim(), imageIds: uploaded.map((image) => image.imageId!), feelingRecordId };
      const signature = JSON.stringify(input);
      if (attempted.current !== signature) {
        key.renew();
        attempted.current = signature;
      }
      const created = await createPost(api, input, where.location, key.current());
      attempted.current = null;
      await postDraftStore.clear().catch(() => undefined);
      await invalidatePostLists(queryClient).catch(() => undefined);
      leaving.current = true;
      router.replace(routes.postDetail(created.postId));
    } catch (cause) {
      await saveDraft();
      if (await writeGate.handleWriteError(cause, action)) {
        return;
      }
      setError(errorText(cause, POST_FAILED));
    } finally {
      setSubmitting(false);
    }
  }

  const neighborhoodLine = editPost
    ? `${editPost.neighborhood.shortName} · 글의 동네는 바꿀 수 없어요`
    : currentId
      ? `${currentName.data?.fullName ?? '현재 동네'} (현재 동네)`
      : '올릴 때 현재 동네를 확인해요';

  return (
    <Screen>
      <View style={styles.block}>
        <AppText variant="caption" tone="secondary">
          {isEdit ? '글 고치기' : '글은 지금 있는 동네(현재 동네)에 올라가요'}
        </AppText>
        <AppText variant="heading" testID="editor-neighborhood">
          {neighborhoodLine}
        </AppText>
        {notice ? (
          <AppText tone="primary" testID="editor-notice">
            {notice}
          </AppText>
        ) : null}
      </View>

      <TextInput
        style={styles.input}
        value={body}
        onChangeText={(text) => {
          setBody(text);
          setError(null);
        }}
        multiline
        placeholder="동네 이웃에게 지금 날씨 이야기를 남겨 보세요"
        placeholderTextColor={colors.textDisabled}
        testID="editor-body"
      />
      <AppText
        variant="caption"
        tone={check.remaining < 0 ? 'danger' : 'secondary'}
        testID="editor-remaining"
      >{`${check.remaining}자 남음`}</AppText>

      <View style={styles.block}>
        <AppText variant="heading">{`사진 ${images.length}/${params.maxPostImages}`}</AppText>
        <View style={styles.images}>
          {images.map((image, index) => (
            <View key={image.key} style={styles.imageBox} testID={`editor-image-${index}`}>
              {image.localUri ? (
                <Image source={{ uri: image.localUri }} style={styles.thumb} accessibilityLabel="고른 사진" />
              ) : image.imageId ? (
                <PostImageView image={{ imageId: image.imageId, url: image.url, width: null, height: null }} size={88} />
              ) : null}
              {image.error ? (
                <AppText variant="caption" tone="danger" testID={`editor-image-error-${index}`}>
                  {image.error}
                </AppText>
              ) : null}
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="사진 빼기"
                onPress={() => setImages((prev) => prev.filter((item) => item.key !== image.key))}
                testID={`editor-image-remove-${index}`}
              >
                <AppText variant="caption" tone="secondary">
                  빼기
                </AppText>
              </Pressable>
            </View>
          ))}
        </View>
        <Button
          title="사진 추가"
          variant="secondary"
          onPress={addImages}
          disabled={remaining <= 0 || submitting}
          pending={adding}
          testID="editor-add-image"
        />
      </View>

      <View style={styles.block}>
        {isEdit ? (
          attached && !detach ? (
            <>
              <AttachedFeelingView record={attached} />
              <Button title="체감 기록 떼기" variant="secondary" onPress={() => setDetach(true)} testID="editor-detach" />
            </>
          ) : attached ? (
            <AppText tone="secondary">체감 기록을 떼고 저장해요</AppText>
          ) : null
        ) : feelingRecordId ? (
          <>
            <AppText testID="editor-feeling-attached">방금 남긴 체감 기록을 함께 올려요</AppText>
            <Button
              title="체감 기록 떼기"
              variant="secondary"
              onPress={() => setFeelingRecordId(null)}
              testID="editor-detach"
            />
          </>
        ) : (
          <AppText variant="caption" tone="secondary">
            체감 기록을 남긴 뒤 &apos;글로도 남기기&apos;를 누르면 글에 함께 붙일 수 있어요
          </AppText>
        )}
      </View>

      {error ? (
        <AppText tone="danger" accessibilityRole="alert" testID="editor-error">
          {error}
        </AppText>
      ) : null}
      <Button
        title={error ? '다시 시도' : isEdit ? '수정 완료' : '올리기'}
        onPress={submit}
        disabled={!check.valid || adding}
        pending={submitting}
        testID="editor-submit"
      />
      {/* attached 상태는 수정 화면에서만 바뀐다 */}
      {isEdit && detach ? (
        <Button
          title="떼기 취소"
          variant="secondary"
          onPress={() => {
            setDetach(false);
            setAttached(editPost?.attachedFeelingRecord ?? null);
          }}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  loading: { marginTop: spacing.xl },
  block: { gap: spacing.sm },
  input: {
    minHeight: 160,
    textAlignVertical: 'top',
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radii.md,
    backgroundColor: colors.surface,
    padding: spacing.md,
    color: colors.textPrimary,
    fontSize: 15,
  },
  images: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  imageBox: { width: 88, gap: 2 },
  thumb: { width: 88, height: 88, borderRadius: radii.sm, backgroundColor: colors.disabledBackground },
});
