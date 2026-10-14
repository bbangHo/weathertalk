// 체감 API (04 FR-FEL-01·02·08, backend feeling/internal/web/FeelingController·CalibrationOfferController)
// 기록·피드백은 Idempotency-Key + 본문에 기기 좌표·정확도(서버가 현재 동네를 확정하고 좌표는 저장하지 않는다).
import type { ApiClient } from '@/api/client';
import type { LocationRequest } from '@/neighborhood/types';

import type {
  CalibrationOffer,
  FeedbackAnswer,
  FeelingRecordResult,
  HumidityTagName,
  SensitivityFeeling,
  TagDefinitions,
  TemperatureTagName,
  WindTagName,
} from './types';

export const FEELING_RECORDS_PATH = '/api/v1/feeling/records';
export const FEELING_FEEDBACKS_PATH = '/api/v1/feeling/feedbacks';

export function getFeelingTags(api: ApiClient): Promise<TagDefinitions> {
  return api.get<TagDefinitions>('/api/v1/feeling/tags', { auth: false });
}

export type RecordSelection = {
  temperature: TemperatureTagName;
  humidity: HumidityTagName | null;
  wind: WindTagName | null;
};

export function postFeelingRecord(
  api: ApiClient,
  selection: RecordSelection,
  location: LocationRequest,
  idempotencyKey: string,
): Promise<FeelingRecordResult> {
  return api.post<FeelingRecordResult>(
    FEELING_RECORDS_PATH,
    { ...selection, ...location },
    { idempotencyKey },
  );
}

/** 보여준 민감도별 체감을 산출 응답 값 그대로 돌려보낸다(BR-FEL-07 검증용). */
export function postFeelingFeedback(
  api: ApiClient,
  answer: FeedbackAnswer,
  shown: SensitivityFeeling,
  location: LocationRequest,
  idempotencyKey: string,
): Promise<FeelingRecordResult> {
  return api.post<FeelingRecordResult>(
    FEELING_FEEDBACKS_PATH,
    {
      answer,
      shown: {
        temperatureTag: shown.temperatureTag,
        source: shown.source,
        mappingVersion: shown.mappingVersion,
        temperatureSensitivity: shown.temperatureSensitivity,
        neighborhoodId: shown.neighborhoodId.value,
        basisTime: shown.basisTime,
        derivedAt: shown.derivedAt,
      },
      ...location,
    },
    { idempotencyKey },
  );
}

export function acceptCalibrationOffer(api: ApiClient, offerId: string): Promise<CalibrationOffer> {
  return api.post<CalibrationOffer>(`/api/v1/members/me/calibration-offers/${encodeURIComponent(offerId)}/accept`);
}

export function declineCalibrationOffer(api: ApiClient, offerId: string): Promise<CalibrationOffer> {
  return api.post<CalibrationOffer>(`/api/v1/members/me/calibration-offers/${encodeURIComponent(offerId)}/decline`);
}
