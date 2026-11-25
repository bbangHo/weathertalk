// 커뮤니티 API (05 §10 구현 메모 웹 API, backend community/internal/web/PostController·CommentController·ImageController)
// 게시글·댓글·답글 작성은 Idempotency-Key, 게시글 작성 본문에는 기기가 판정한 현재 동네 ID(좌표는 보내지 않는다, ADR 0009).
import type { ApiClient } from '@/api/client';

import type { CommentPage, LikeState, PostDetail, PostPage, PostSummary, UploadedImage, UploadTicket } from './types';

const enc = encodeURIComponent;

export function fetchFeed(
  api: ApiClient,
  neighborhoodId: string,
  cursor: string | null,
  size?: number,
  signal?: AbortSignal,
): Promise<PostPage> {
  return api.get<PostPage>(`/api/v1/community/neighborhoods/${enc(neighborhoodId)}/posts`, {
    query: { cursor, size },
    signal,
  });
}

export function fetchMyPosts(api: ApiClient, cursor: string | null, size?: number): Promise<PostPage> {
  return api.get<PostPage>('/api/v1/members/me/posts', { query: { cursor, size } });
}

export function fetchPost(api: ApiClient, postId: string, signal?: AbortSignal): Promise<PostDetail> {
  return api.get<PostDetail>(`/api/v1/community/posts/${enc(postId)}`, { signal });
}

export function fetchComments(api: ApiClient, postId: string, cursor: string): Promise<CommentPage> {
  return api.get<CommentPage>(`/api/v1/community/posts/${enc(postId)}/comments`, { query: { cursor } });
}

export type CreatePostInput = { body: string; imageIds: string[]; feelingRecordId: string | null };

export function createPost(
  api: ApiClient,
  input: CreatePostInput,
  neighborhoodId: string,
  idempotencyKey: string,
): Promise<{ postId: string }> {
  return api.post<{ postId: string }>('/api/v1/community/posts', { ...input, neighborhoodId }, { idempotencyKey });
}

/** 수정은 최종 상태(본문·이미지 목록·첨부 해제 여부)를 보낸다. 동네·좌표는 보내지 않는다(BR-COM-07). */
export function updatePost(
  api: ApiClient,
  postId: string,
  input: { body: string; imageIds: string[]; detachFeelingRecord: boolean },
): Promise<PostSummary> {
  return api.put<PostSummary>(`/api/v1/community/posts/${enc(postId)}`, input);
}

export function deletePost(api: ApiClient, postId: string): Promise<void> {
  return api.delete<void>(`/api/v1/community/posts/${enc(postId)}`);
}

/** 토글이 아니라 원하는 최종 상태를 보낸다(BR-COM-11). */
export function setLike(api: ApiClient, postId: string, liked: boolean): Promise<LikeState> {
  return api.put<LikeState>(`/api/v1/community/posts/${enc(postId)}/like`, { liked });
}

export function createComment(
  api: ApiClient,
  postId: string,
  body: string,
  idempotencyKey: string,
): Promise<{ commentId: string }> {
  return api.post<{ commentId: string }>(`/api/v1/community/posts/${enc(postId)}/comments`, { body }, { idempotencyKey });
}

export function createReply(
  api: ApiClient,
  parentCommentId: string,
  body: string,
  idempotencyKey: string,
): Promise<{ commentId: string }> {
  return api.post<{ commentId: string }>(
    `/api/v1/community/comments/${enc(parentCommentId)}/replies`,
    { body },
    { idempotencyKey },
  );
}

export function deleteComment(api: ApiClient, commentId: string): Promise<void> {
  return api.delete<void>(`/api/v1/community/comments/${enc(commentId)}`);
}

/** 업로드 ① 사전 서명 PUT 주소 발급. contentLength는 서명에 들어가므로 실제 파일 크기와 같아야 한다. */
export function requestImageUpload(api: ApiClient, contentType: string, contentLength: number): Promise<UploadTicket> {
  return api.post<UploadTicket>('/api/v1/community/images', { contentType, contentLength });
}

/** 업로드 ③ 확인: 서버가 메타데이터를 지우고 다시 인코딩한 미첨부 이미지 */
export function completeImageUpload(api: ApiClient, imageId: string): Promise<UploadedImage> {
  return api.post<UploadedImage>(`/api/v1/community/images/${enc(imageId)}/complete`);
}
