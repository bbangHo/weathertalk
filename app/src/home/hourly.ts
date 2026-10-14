// 시간별 예보 목록 구성 (07 §1.2 시간별 예보, §8 #20): 받은 순서·항목 그대로, 시(時) 기준 중복 제거 금지, 날짜 경계 표시.
import type { SectionStatus as UiSectionStatus } from '@/components/ui';

import type { HomeSection, HourlyForecastSlot } from './types';

export type HourlyItem =
  | { kind: 'date'; key: string; date: string }
  | { kind: 'slot'; key: string; slot: HourlyForecastSlot };

/** 날짜가 바뀌는 칸 앞에 날짜 표시를 끼운다. 칸 식별은 순번으로 해 같은 시각이 와도 지우지 않는다. */
export function buildHourlyItems(slots: readonly HourlyForecastSlot[]): HourlyItem[] {
  const items: HourlyItem[] = [];
  slots.forEach((slot, index) => {
    const previous = index > 0 ? slots[index - 1] : null;
    if (previous && previous.date !== slot.date) {
      items.push({ kind: 'date', key: `date-${index}`, date: slot.date });
    }
    items.push({ kind: 'slot', key: `slot-${index}`, slot });
  });
  return items;
}

/** 서버 섹션 상태 → 공통 섹션 표시 상태 */
export function uiStatus(section: HomeSection<unknown>): UiSectionStatus {
  switch (section.status) {
    case 'OK':
      return section.data === null ? 'failed' : 'ok';
    case 'EMPTY':
      return 'empty';
    default:
      return 'failed';
  }
}
