// 현장 제보 API (03 FR-WEA-04·05·14, backend weather/internal/web/FieldReportController)
// 제보는 Idempotency-Key + 본문에 기기가 판정한 현재 동네 ID(좌표는 보내지 않는다, ADR 0009).
import type { ApiClient } from '@/api/client';

import type { FieldReportKind, FieldReportResult, FieldReportStatus } from './types';

export const FIELD_REPORTS_PATH = '/api/v1/weather/field-reports';

export function submitFieldReport(
  api: ApiClient,
  kind: FieldReportKind,
  neighborhoodId: string,
  idempotencyKey: string,
): Promise<FieldReportResult> {
  return api.post<FieldReportResult>(FIELD_REPORTS_PATH, { kind, neighborhoodId }, { idempotencyKey });
}

export function cancelFieldReport(api: ApiClient, reportId: string): Promise<FieldReportResult> {
  return api.post<FieldReportResult>(`${FIELD_REPORTS_PATH}/${encodeURIComponent(reportId)}/cancel`);
}

/** 비회원도 볼 수 있다. 회원이면 본인 제보(myReports)가 함께 온다. */
export function getFieldReportStatus(api: ApiClient, neighborhoodId: string): Promise<FieldReportStatus> {
  return api.get<FieldReportStatus>(`/api/v1/weather/neighborhoods/${encodeURIComponent(neighborhoodId)}/field-reports`);
}
