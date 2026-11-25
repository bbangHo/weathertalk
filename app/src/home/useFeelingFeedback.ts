// SCR-01 체감 피드백 (07 SCR-01 피드백 버튼 노출 규칙, §3.1, 04 FR-FEL-02·BR-FEL-07, ADR 0011)
// 비회원 → 가입 유도(보류 행동). 회원 → 위치 확인(오래됐으면 재판정) → 저장. 실패는 항상 화면에 알린다.
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { api } from '@/api';
import { ErrorCodes, isApiError } from '@/api/errors';
import { useIdempotencyKey } from '@/api/idempotency';
import { postFeelingFeedback } from '@/feeling/api';
import type { FeedbackAnswer, SensitivityFeeling } from '@/feeling/types';
import { prepareWriteLocation } from '@/location/writeLocation';
import { viewingStore } from '@/neighborhood/viewingStore';
import type { PendingAction } from '@/pending/pendingAction';
import { writeGate } from '@/pending/writeGate';
import { queryKeys } from '@/query/queryKeys';

export type FeedbackState =
  | { status: 'idle' }
  | { status: 'saved'; answer: FeedbackAnswer }
  | { status: 'error'; message: string };

export const NOT_CURRENT_FEEDBACK = '현재 동네에서만 답할 수 있어요';
export const LOCATION_UNAVAILABLE_FEEDBACK = '현재 동네를 확인할 수 없어 답할 수 없어요';

export function useFeelingFeedback(shown: SensitivityFeeling | null, viewingNeighborhoodId: string) {
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const lastAnswer = useRef<FeedbackAnswer | null>(null);
  const [state, setState] = useState<FeedbackState>({ status: 'idle' });

  async function submit(answer: FeedbackAnswer): Promise<void> {
    if (!shown) {
      return;
    }
    const action: PendingAction = {
      kind: 'feedback',
      route: '/',
      neighborhoodId: viewingNeighborhoodId,
      payload: { answer, group: shown.temperatureSensitivity, temperatureTag: shown.temperatureTag },
    };
    if (!(await writeGate.requireMember(action))) {
      return;
    }
    // 같은 답을 다시 누르면 재시도(같은 식별자), 다른 답이면 새 행동이다.
    if (lastAnswer.current !== answer) {
      key.renew();
      lastAnswer.current = answer;
    }
    setState({ status: 'idle' });
    const where = await prepareWriteLocation({ store: viewingStore });
    if (where.status !== 'ok') {
      setState({ status: 'error', message: LOCATION_UNAVAILABLE_FEEDBACK });
      return;
    }
    if (where.neighborhoodId !== shown.neighborhoodId.value) {
      setState({ status: 'error', message: NOT_CURRENT_FEEDBACK });
      return;
    }
    try {
      await postFeelingFeedback(api, answer, shown, where.neighborhoodId, key.current());
      key.renew();
      lastAnswer.current = null;
      setState({ status: 'saved', answer });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.home.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.me.feelingRecords() }),
      ]);
    } catch (error) {
      if (await writeGate.handleWriteError(error, action)) {
        return;
      }
      const detail = isApiError(error) ? error.detail : null;
      if (isApiError(error) && error.code === 'FEEDBACK_TARGET_INVALID') {
        // 보여준 값이 오래됐거나 다르다 — 최신 값을 다시 불러온다(04 FR-FEL-02 실패).
        setState({ status: 'error', message: detail ?? '체감이 바뀌었어요. 새로 불러온 뒤 다시 답해 주세요.' });
        queryClient.invalidateQueries({ queryKey: queryKeys.home.all }).catch(() => undefined);
        return;
      }
      if (isApiError(error, ErrorCodes.CURRENT_NEIGHBORHOOD_UNVERIFIED)) {
        setState({ status: 'error', message: detail ?? LOCATION_UNAVAILABLE_FEEDBACK });
        return;
      }
      setState({ status: 'error', message: detail ?? '저장하지 못했어요. 다시 눌러 주세요.' });
    }
  }

  return { state, submit };
}
