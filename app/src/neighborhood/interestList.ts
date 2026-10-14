// 관심 동네 목록 규칙 (02 BR-NBR-08: 최대 개수·중복 금지, 비회원 기기도 같은 규칙). 순수 함수.

export class InterestLimitError extends Error {
  constructor(readonly max: number) {
    super(`관심 동네는 ${max}개까지 추가할 수 있어요.`);
    this.name = 'InterestLimitError';
  }
}

/** 끝에 추가. 이미 있으면 그대로 돌려준다. */
export function addInterestId(ids: readonly string[], id: string, max: number): string[] {
  if (ids.includes(id)) {
    return [...ids];
  }
  if (ids.length >= max) {
    throw new InterestLimitError(max);
  }
  return [...ids, id];
}

/** 해제는 없는 동네여도 성공(멱등, FR-NBR-05). */
export function removeInterestId(ids: readonly string[], id: string): string[] {
  return ids.filter((item) => item !== id);
}

/** 한 칸 위(-1)·아래(+1)로. 범위를 벗어나면 그대로. */
export function moveInterestId(ids: readonly string[], id: string, delta: -1 | 1): string[] {
  const from = ids.indexOf(id);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= ids.length) {
    return [...ids];
  }
  const next = [...ids];
  next[from] = ids[to];
  next[to] = id;
  return next;
}

/** 저장소에서 읽은 값이 깨졌어도 규칙을 지키는 목록으로 맞춘다(중복 제거·최대 개수). */
export function normalizeInterestIds(raw: unknown, max: number): string[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const unique = [...new Set(raw.filter((item): item is string => typeof item === 'string' && item.length > 0))];
  return unique.slice(0, max);
}
