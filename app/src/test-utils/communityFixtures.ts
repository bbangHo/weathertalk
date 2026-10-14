// 커뮤니티 응답 테스트 데이터 (backend PostSummary·PostPage·CommentView 형식)
import type { CommentView, PostDetail, PostPage, PostSummary } from '@/community/types';

export function post(id: string, overrides: Partial<PostSummary> = {}): PostSummary {
  return {
    postId: id,
    body: `본문 ${id}`,
    images: [],
    neighborhood: { id: 'N1', name: '역삼1동', shortName: '강남구 역삼1동' },
    attachedFeelingRecord: null,
    createdAt: '2026-10-15T00:00:00Z',
    edited: false,
    updatedAt: null,
    likeCount: 2,
    commentCount: 0,
    author: { nickname: `작성자${id}`, temperatureSensitivity: 'NORMAL' },
    likedByViewer: false,
    mine: false,
    ...overrides,
  };
}

export function page(items: PostSummary[], nextCursor: string | null = null): PostPage {
  return { items, nextCursor };
}

export function comment(id: string, overrides: Partial<CommentView> = {}): CommentView {
  return {
    commentId: id,
    deleted: false,
    body: `댓글 ${id}`,
    author: { nickname: `댓글러${id}`, temperatureSensitivity: 'COLD_SENSITIVE' },
    createdAt: '2026-10-15T00:10:00Z',
    edited: false,
    mine: false,
    replies: [],
    ...overrides,
  };
}

export function detail(p: PostSummary, comments: CommentView[] = [], nextCursor: string | null = null): PostDetail {
  return { post: p, comments: { items: comments, nextCursor } };
}
