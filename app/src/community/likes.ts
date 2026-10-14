// 좋아요 (05 FR-COM-11·BR-COM-10·11, 07 SCR-06·07 좋아요, §3 규칙 4 좋아요만 자동 적용)
// - 화면은 바로 바꾸고(낙관적) 서버에는 원하는 최종 상태를 보낸다. 실패하면 되돌리고 알린다.
// - 비회원은 보류 행동(좋아요)을 남기고 가입 시트를 연다. 가입·로그인 뒤 아래 자동 적용 처리기가 서버에 보낸다.
import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import { useCallback, useState } from 'react';

import { api } from '@/api';
import type { ApiClient } from '@/api/client';
import { ErrorCodes, isApiError } from '@/api/errors';
import { type PendingActionOf, registerAutoApply } from '@/pending/pendingAction';
import { writeGate } from '@/pending/writeGate';
import { getActiveQueryClient } from '@/query/activeClient';

import { setLike } from './api';
import { invalidatePostLists, likePatch, patchPostEverywhere } from './postCache';
import type { PostSummary } from './types';

export const SELF_LIKE_NOTICE = '내 글에는 좋아요할 수 없어요';
export const LIKE_FAILED = '좋아요를 반영하지 못했어요. 다시 눌러 주세요.';

/** 보류된 좋아요를 서버에 보내고 결과를 캐시에 반영한다. 실패는 던져 복원 쪽이 안내를 붙이게 한다. */
export function createLikeAutoApply(deps: { api: ApiClient; getClient: () => QueryClient | null }) {
  return async (action: PendingActionOf<'like'>): Promise<void> => {
    const state = await setLike(deps.api, action.payload.postId, action.payload.liked);
    const client = deps.getClient();
    if (client) {
      patchPostEverywhere(client, action.payload.postId, likePatch(state.liked, state.likeCount));
      // 로그인 직후 캐시가 비워졌을 수 있어 목록도 다시 부른다(조회자 좋아요 여부가 회원 기준으로 바뀜).
      await invalidatePostLists(client).catch(() => undefined);
    }
  };
}

// 모듈을 불러오는 순간 등록한다(루트 레이아웃이 불러온다) — 어느 화면에서 가입했든 복귀 때 적용된다.
registerAutoApply('like', createLikeAutoApply({ api, getClient: getActiveQueryClient }));

/** 화면에서 쓰는 좋아요. route는 가입 후 돌아올 화면 경로(§3.1 원래 화면). */
export function useLikeAction(route: string) {
  const queryClient = useQueryClient();
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());
  const [notice, setNotice] = useState<string | null>(null);

  const toggle = useCallback(
    async (post: PostSummary): Promise<void> => {
      if (post.mine) {
        setNotice(SELF_LIKE_NOTICE);
        return;
      }
      const next = !post.likedByViewer;
      const action = {
        kind: 'like' as const,
        route,
        neighborhoodId: null,
        payload: { postId: post.postId, liked: next },
      };
      if (!(await writeGate.requireMember(action))) {
        return;
      }
      const before = { liked: Boolean(post.likedByViewer), count: post.likeCount };
      setNotice(null);
      setPending((set) => new Set(set).add(post.postId));
      patchPostEverywhere(queryClient, post.postId, likePatch(next, before.count + (next ? 1 : -1)));
      try {
        const state = await setLike(api, post.postId, next);
        patchPostEverywhere(queryClient, post.postId, likePatch(state.liked, state.likeCount));
      } catch (error) {
        patchPostEverywhere(queryClient, post.postId, likePatch(before.liked, before.count));
        if (await writeGate.handleWriteError(error, action)) {
          return;
        }
        if (isApiError(error, ErrorCodes.NOT_FOUND)) {
          setNotice('삭제된 글이에요');
          await invalidatePostLists(queryClient).catch(() => undefined);
          return;
        }
        setNotice(
          isApiError(error) && error.code === 'SELF_LIKE_NOT_ALLOWED'
            ? SELF_LIKE_NOTICE
            : isApiError(error) && error.detail
              ? error.detail
              : LIKE_FAILED,
        );
      } finally {
        setPending((set) => {
          const copy = new Set(set);
          copy.delete(post.postId);
          return copy;
        });
      }
    },
    [queryClient, route],
  );

  return { toggle, isPending: (postId: string) => pending.has(postId), notice, setNotice };
}
