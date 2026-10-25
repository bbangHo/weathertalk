// 현장 제보·차이 배지 테스트 데이터 (backend weather FieldReportStatus·FieldReport·ForecastDivergence 형식)
import type {
  AxisDivergence,
  AxisStatus,
  FieldReport,
  FieldReportAxis,
  FieldReportKind,
  FieldReportStatus,
  ForecastDivergence,
} from '@/fieldReport/types';

export function axisStatus(axis: FieldReportAxis, counts: [FieldReportKind, number][] = []): AxisStatus {
  const total = counts.reduce((sum, [, n]) => sum + n, 0);
  return {
    axis,
    reporterCount: total,
    counts: counts.map(([kind, reporterCount]) => ({ kind, reporterCount })),
    majorityKind: total >= 3 ? counts[0][0] : null,
    latestReportedAt: total > 0 ? '2026-10-15T02:50:00Z' : null,
  };
}

export function myReport(
  axis: FieldReportAxis,
  kind: FieldReportKind,
  cancelableUntil: string,
  reportId = `report-${kind}`,
): FieldReport {
  return {
    reportId,
    neighborhoodId: { value: 'N1' },
    axis,
    kind,
    reportedAt: '2026-10-15T03:00:00Z',
    cancelableUntil,
    canceledAt: null,
  };
}

export function fieldReportStatus(overrides: Partial<FieldReportStatus> = {}, neighborhoodId = 'N1'): FieldReportStatus {
  return {
    neighborhoodId: { value: neighborhoodId },
    aggregatedAt: '2026-10-15T03:00:00Z',
    axes: [axisStatus('SKY_PRECIPITATION'), axisStatus('AIR')],
    myReports: [],
    ...overrides,
  };
}

export function axisDivergence(overrides: Partial<AxisDivergence> = {}): AxisDivergence {
  return {
    axis: 'PRECIPITATION',
    badge: false,
    basisValue: null,
    basisLabel: null,
    basisSource: null,
    basisTime: null,
    residentKind: null,
    reporterCount: 0,
    majorityCount: 0,
    ...overrides,
  };
}

export function divergence(axes: AxisDivergence[]): ForecastDivergence {
  return { neighborhoodId: { value: 'N1' }, judgedAt: '2026-10-15T03:00:00Z', axes };
}
