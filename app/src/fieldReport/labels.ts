// 현장 제보 표시 문구와 취소 기한 (03 §3.7·3.8, FR-WEA-14, 07 SCR-01 5·8번, SCR-05)
// 판정(다수 의견·배지)은 weather가 하고 앱은 받은 값을 글자로만 바꾼다.
import { kstTimeText, NO_DATA } from '@/home/format';
import type { ValueSource } from '@/home/types';

import type { AxisDivergence, FieldReport, FieldReportAxis, FieldReportKind } from './types';

export const KIND_LABEL: Record<FieldReportKind, string> = {
  RAIN: '비',
  SNOW: '눈',
  OVERCAST: '흐림',
  CLEAR: '맑음',
  DUST_BAD: '미세먼지 나쁨',
  AIR_CLEAN: '공기 깨끗함',
};

export const AXIS_LABEL: Record<FieldReportAxis, string> = { SKY_PRECIPITATION: '하늘·강수', AIR: '대기' };

/** 축별 종류(07 SCR-05 순서). 안개·우박은 없다. */
export const AXIS_KINDS: Record<FieldReportAxis, readonly FieldReportKind[]> = {
  SKY_PRECIPITATION: ['RAIN', 'SNOW', 'OVERCAST', 'CLEAR'],
  AIR: ['DUST_BAD', 'AIR_CLEAN'],
};

export const AXES: readonly FieldReportAxis[] = ['SKY_PRECIPITATION', 'AIR'];

export const CANCEL_WINDOW_PASSED_MESSAGE = '제보 후 10분이 지나 취소할 수 없어요';
export const REPORT_LOCATION_UNAVAILABLE = '현재 동네를 확인할 수 없어 제보할 수 없어요';
export const NOT_CURRENT_REPORT = '현장 제보는 현재 동네에서만 할 수 있어요';

export function kindText(kind: FieldReportKind | null): string {
  return kind ? KIND_LABEL[kind] : NO_DATA;
}

/** 취소 버튼 노출: 취소하지 않았고 서버가 준 기한 안(경계 포함, 03 §11 M5 메모). */
export function isCancelable(report: FieldReport, nowMs: number): boolean {
  if (report.canceledAt) {
    return false;
  }
  const until = Date.parse(report.cancelableUntil);
  return Number.isFinite(until) && nowMs <= until;
}

/** 아직 지나지 않은 가장 이른 취소 기한(ms). 없으면 null — 그 시각에 버튼을 숨기려고 쓴다. */
export function nextCancelDeadline(reports: readonly FieldReport[], nowMs: number): number | null {
  const upcoming = reports.filter((report) => isCancelable(report, nowMs)).map((report) => Date.parse(report.cancelableUntil));
  return upcoming.length > 0 ? Math.min(...upcoming) : null;
}

// ── 예보 vs 주민 차이 배지 (BR-WEA-19: 판정·값은 weather, 앱은 문장으로만 잇는다) ──

const SOURCE_TOPIC: Record<ValueSource, string> = { FORECAST: '예보는', OBSERVATION: '실황은', MEASUREMENT: '측정값은' };
const SOURCE_BASIS: Record<ValueSource, string> = { FORECAST: '예보', OBSERVATION: '관측', MEASUREMENT: '측정' };

/** 예: "예보는 맑음, 주민 제보는 비" */
export function divergenceText(axis: AxisDivergence): string {
  const topic = axis.basisSource ? SOURCE_TOPIC[axis.basisSource] : '예보는';
  return `${topic} ${axis.basisLabel ?? NO_DATA}, 주민 제보는 ${kindText(axis.residentKind)}`;
}

/** 예: "08:00 예보 기준 · 주민 5명 중 4명" */
export function divergenceBasisText(axis: AxisDivergence): string {
  const time = kstTimeText(axis.basisTime);
  const basis = axis.basisSource ? `${SOURCE_BASIS[axis.basisSource]} 기준` : '기준';
  return [time ? `${time} ${basis}` : '기준 시각 정보 없음', `주민 ${axis.reporterCount}명 중 ${axis.majorityCount}명`].join(
    ' · ',
  );
}
