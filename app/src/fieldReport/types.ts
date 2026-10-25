// 현장 제보·예보 vs 주민 차이 응답 형식 (03 §3.7·3.8, FR-WEA-04·05·12·14, backend weather/FieldReport·FieldReportStatus·
// FieldReportResult·ForecastDivergence). 시각(Instant)은 ISO 문자열. 판정은 weather가 하고 앱은 표시만 한다.
import type { NeighborhoodIdValue, ValueSource } from '@/home/types';

export type FieldReportAxis = 'SKY_PRECIPITATION' | 'AIR';
export type FieldReportKind = 'RAIN' | 'SNOW' | 'OVERCAST' | 'CLEAR' | 'DUST_BAD' | 'AIR_CLEAN';

/** 본인 제보 한 건. 제보자 회원 ID는 오지 않는다. */
export type FieldReport = {
  reportId: string;
  neighborhoodId: NeighborhoodIdValue;
  axis: FieldReportAxis;
  kind: FieldReportKind;
  reportedAt: string;
  /** 이 시각까지 본인이 취소할 수 있다(제보 시각 + cancel-window, 경계 포함) */
  cancelableUntil: string;
  canceledAt: string | null;
};

export type KindCount = { kind: FieldReportKind; reporterCount: number };

export type AxisStatus = {
  axis: FieldReportAxis;
  reporterCount: number;
  /** 0명 종류 제외, 많은 순(서버 순서 그대로) */
  counts: KindCount[];
  /** 다수 의견(BR-WEA-18). 성립하지 않으면 null */
  majorityKind: FieldReportKind | null;
  latestReportedAt: string | null;
};

export type FieldReportStatus = {
  neighborhoodId: NeighborhoodIdValue;
  aggregatedAt: string;
  axes: AxisStatus[];
  /** 회원이면 축별 본인 유효 제보, 비회원은 빈 목록 */
  myReports: FieldReport[];
};

export type FieldReportResult = { report: FieldReport; status: FieldReportStatus };

export type DivergenceAxis = 'PRECIPITATION' | 'SKY' | 'AIR';

export type AxisDivergence = {
  axis: DivergenceAxis;
  /** 배지 표시 여부. 앱은 이 값만 보고 보여준다 */
  badge: boolean;
  basisValue: string | null;
  /** 기준 측 값 표시문(예: "강수 없음", "흐림", "좋음") */
  basisLabel: string | null;
  basisSource: ValueSource | null;
  /** 실황 관측 시각 / 예보 발표 시각 / 대기질 측정 시각 */
  basisTime: string | null;
  residentKind: FieldReportKind | null;
  reporterCount: number;
  majorityCount: number;
};

export type ForecastDivergence = {
  neighborhoodId: NeighborhoodIdValue;
  judgedAt: string;
  axes: AxisDivergence[];
};
