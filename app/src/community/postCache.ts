// 게시글 캐시 갱신 (07 §5 데이터 변경 후 갱신, 05 §9 화면: 좋아요 응답의 상태·수로 화면 갱신 — 전체 재조회 금지)
// 같은 게시글이 피드·상세·홈 인기글·내가 쓴 글에 동시에 있으므로 한 곳에서 모두 고친다.
import type { InfiniteData, QueryClient } from '@tanstack/react-query';

import type { HomeView } from '@/home/types';
import { queryKeys } from '@/query/queryKeys';

import type { PostDetail, PostPage, PostSummary } from './types';

type Patch = (post: PostSummary) => PostSummary;

function patchPage(page: PostPage, postId: string, patch: Patch): PostPage {
  return { ...page, items: page.items.map((item) => (item.postId === postId ? patch(item) : item)) };
}

function patchInfinite(data: InfiniteData<PostPage> | undefined, postId: string, patch: Patch) {
  return data ? { ...data, pages: data.pages.map((page) => patchPage(page, postId, patch)) } : data;
}

function isInfinite(value: unknown): value is InfiniteData<PostPage> {
  return typeof value === 'object' && value !== null && Array.isArray((value as InfiniteData<PostPage>).pages);
}

/** 캐시에 있는 그 게시글을 모두 고친다. */
export function patchPostEverywhere(client: QueryClient, postId: string, patch: Patch): void {
  client.setQueriesData<InfiniteData<PostPage>>({ queryKey: queryKeys.community.feeds() }, (data) =>
    patchInfinite(data, postId, patch),
  );
  client.setQueriesData<unknown>({ queryKey: queryKeys.me.posts() }, (data: unknown) => {
    if (isInfinite(data)) {
      return patchInfinite(data, postId, patch);
    }
    if (typeof data === 'object' && data !== null && Array.isArray((data as PostPage).items)) {
      return patchPage(data as PostPage, postId, patch);
    }
    return data;
  });
  client.setQueryData<PostDetail>(queryKeys.community.post(postId), (data) =>
    data ? { ...data, post: patch(data.post) } : data,
  );
  client.setQueriesData<HomeView>({ queryKey: queryKeys.home.all }, (data) => {
    const posts = data?.popularPosts?.data;
    if (!data || !posts) {
      return data;
    }
    return {
      ...data,
      popularPosts: { ...data.popularPosts, data: posts.map((item) => (item.postId === postId ? patch(item) : item)) },
    };
  });
}

/** 좋아요 상태 표시. 서버 응답이 있으면 그 값을 그대로 쓴다. */
export function likePatch(liked: boolean, likeCount: number): Patch {
  return (post) => ({ ...post, likedByViewer: liked, likeCount: Math.max(0, likeCount) });
}

/** 글 작성·수정·삭제 뒤 다시 불러올 목록들 (SCR-08 결과: 피드·홈 인기글 무효화) */
export async function invalidatePostLists(client: QueryClient): Promise<void> {
  await Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.community.feeds() }),
    client.invalidateQueries({ queryKey: queryKeys.home.all }),
    client.invalidateQueries({ queryKey: queryKeys.me.posts() }),
  ]);
}
