// 체감 표시 문구 (07 SCR-01 4번 산출 출처 문구, 04 §3.1 태그). 태그 이름의 정본은 GET /api/v1/feeling/tags이고,
// 아래 표는 그 응답을 아직 받지 못했을 때(홈 첫 표시·오프라인)만 쓰는 대체 문구다. 서버 값과 같게 둔다.
import { useQuery } from '@tanstack/react-query';

import { api } from '@/api';
import { queryKeys } from '@/query/queryKeys';

import { getFeelingTags } from './api';
import type { DerivationSource, TagDefinitions } from './types';

export const SOURCE_TEXT: Record<DerivationSource, string> = {
  NEIGHBORHOOD_FEELING: '이 동네 주민 기록',
  FEELING_MAPPING: '비슷한 날씨의 기록',
  REFERENCE_TABLE: '기본 기준',
};

const FALLBACK: Record<string, string> = {
  VERY_COLD: '매우 추움',
  COLD: '추움',
  SLIGHTLY_COLD: '조금 추움',
  JUST_RIGHT: '딱 좋음',
  SLIGHTLY_HOT: '조금 더움',
  HOT: '더움',
  VERY_HOT: '매우 더움',
  VERY_DRY: '매우 건조해요',
  DRY: '건조해요',
  COMFORTABLE: '쾌적해요',
  HUMID: '습해요',
  VERY_HUMID: '매우 습해요',
  CALM: '잔잔해요',
  LIGHT: '조금 불어요',
  STRONG: '세게 불어요',
};

export type TagLabeler = (name: string | null | undefined) => string | null;

export function createTagLabeler(definitions: TagDefinitions | undefined): TagLabeler {
  const byName = new Map<string, string>();
  for (const list of definitions ? [definitions.temperature, definitions.humidity, definitions.wind] : []) {
    for (const item of list) {
      byName.set(item.name, item.label);
    }
  }
  return (name) => (name ? (byName.get(name) ?? FALLBACK[name] ?? name) : null);
}

/** 태그 정의는 배포 사이에 바뀌지 않으니 한 번 받으면 다시 부르지 않는다. */
export function useFeelingTags() {
  return useQuery({
    queryKey: queryKeys.feeling.tags(),
    queryFn: () => getFeelingTags(api),
    staleTime: Infinity,
  });
}
