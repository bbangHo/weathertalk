// 커뮤니티 응답 형식 (05 FR-COM-05·06·10·11·16, §10 구현 메모, backend community/PostPage·PostSummary,
// internal/PostQueryService.PostDetail·CommentPage·CommentView, LikeService.LikeState, PostImageService.UploadTicket·UploadedImage)
import type { HumidityTagName, TemperatureTagName, WindTagName } from '@/feeling/types';
import type { TemperatureSensitivity } from '@/storage';

/** url은 사전 서명 주소라 조회마다 바뀐다 — 캐시 키는 imageId(owner-todos §6). 저장소 미설정이면 null */
export type PostImage = { imageId: string; url: string | null; width: number | null; height: number | null };

export type PostNeighborhood = { id: string; name: string; shortName: string };

export type AttachedFeelingRecord = {
  recordId: string;
  temperature: TemperatureTagName;
  humidity: HumidityTagName | null;
  wind: WindTagName | null;
  recordedAt: string;
  temperatureSensitivity: TemperatureSensitivity;
};

export type PostAuthor = { nickname: string; temperatureSensitivity: TemperatureSensitivity };

export type PostSummary = {
  postId: string;
  body: string;
  images: PostImage[];
  neighborhood: PostNeighborhood;
  attachedFeelingRecord: AttachedFeelingRecord | null;
  createdAt: string;
  edited: boolean;
  updatedAt: string | null;
  likeCount: number;
  commentCount: number;
  author: PostAuthor;
  /** 비회원이면 null */
  likedByViewer: boolean | null;
  /** 비회원이면 null */
  mine: boolean | null;
};

/** nextCursor가 null이면 마지막 페이지 */
export type PostPage = { items: PostSummary[]; nextCursor: string | null };

/** 삭제된 댓글 자리는 deleted=true, body·author가 null */
export type CommentView = {
  commentId: string;
  deleted: boolean;
  body: string | null;
  author: PostAuthor | null;
  createdAt: string;
  edited: boolean;
  mine: boolean | null;
  /** 댓글이면 답글 전부(오래된순), 답글이면 빈 목록 */
  replies: CommentView[];
};

export type CommentPage = { items: CommentView[]; nextCursor: string | null };

export type PostDetail = { post: PostSummary; comments: CommentPage };

export type LikeState = { liked: boolean; likeCount: number };

export type UploadTicket = { imageId: string; uploadUrl: string; headers: Record<string, string>; expiresAt: string };

export type UploadedImage = { imageId: string; url: string | null; width: number; height: number; contentType: string };
