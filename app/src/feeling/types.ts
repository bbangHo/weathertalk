// 체감 응답 형식 (04 §3, backend feeling/SensitivityFeeling·SensitivityFeelings·RecentFeelingRecord·YesterdayComparison·
// CalibrationOffer, feeling/internal/FeelingRecordResult, home/FeedbackStatus). 태그는 이름 문자열로 주고받는다.
import type { NeighborhoodIdValue } from '@/home/types';
import type { TemperatureSensitivity } from '@/storage';

export type TemperatureTagName =
  | 'VERY_COLD'
  | 'COLD'
  | 'SLIGHTLY_COLD'
  | 'JUST_RIGHT'
  | 'SLIGHTLY_HOT'
  | 'HOT'
  | 'VERY_HOT';
export type HumidityTagName = 'VERY_DRY' | 'DRY' | 'COMFORTABLE' | 'HUMID' | 'VERY_HUMID';
export type WindTagName = 'CALM' | 'LIGHT' | 'STRONG';

export type DerivationSource = 'NEIGHBORHOOD_FEELING' | 'FEELING_MAPPING' | 'REFERENCE_TABLE';
export type FeedbackAnswer = 'RIGHT' | 'COLDER' | 'HOTTER';
export type FeelingRecordSource = 'DIRECT' | 'FEEDBACK' | 'DIRECT_WITH_FEEDBACK';

export type SensitivityFeeling = {
  neighborhoodId: NeighborhoodIdValue;
  temperatureSensitivity: TemperatureSensitivity;
  basisTime: string;
  timeKind: 'CURRENT' | 'FUTURE' | 'PAST';
  temperatureTag: TemperatureTagName;
  humidityTag: HumidityTagName | null;
  windTag: WindTagName | null;
  source: DerivationSource;
  sampleCount: number | null;
  mappingVersion: number | null;
  mappingLayer: string | null;
  weatherBasedTemperatureTag: TemperatureTagName | null;
  derivedAt: string;
};

/** 산출 불가 그룹은 groups에서 빠진다 */
export type SensitivityFeelings = {
  neighborhoodId: NeighborhoodIdValue;
  basisTime: string | null;
  groups: SensitivityFeeling[];
};

export type RecentFeelingRecord = {
  id: { value: string };
  source: FeelingRecordSource;
  temperatureTag: TemperatureTagName;
  recordedAt: string;
  createdAt: string;
  overwritableUntil: string;
};

/** home FeedbackStatus: available = 보는 동네가 회원의 현재 동네인가 */
export type FeedbackStatus = {
  available: boolean;
  recentRecord: RecentFeelingRecord | null;
};

export type YesterdayComparison = {
  neighborhoodId: NeighborhoodIdValue;
  temperatureSensitivity: TemperatureSensitivity;
  comparable: boolean;
  levelDifference: number | null;
  phrase: string | null;
  today: SensitivityFeeling | null;
  yesterday: SensitivityFeeling | null;
};

export type CalibrationOfferStatus = 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'INVALIDATED';

export type CalibrationOffer = {
  offerId: string;
  currentGroup: TemperatureSensitivity;
  suggestedGroup: TemperatureSensitivity;
  recordCount: number;
  weightedRecordCount: number;
  errors: Partial<Record<TemperatureSensitivity, number>>;
  judgedAt: string;
  expiresAt: string;
  status: CalibrationOfferStatus;
};

/** 체감 기록·피드백 저장 결과 */
export type FeelingRecordResult = {
  recordId: string;
  neighborhoodId: string;
  createdAt: string;
  recordedAt: string;
  temperatureTag: TemperatureTagName;
  humidityTag: HumidityTagName | null;
  windTag: WindTagName | null;
  temperatureSensitivity: TemperatureSensitivity;
  source: FeelingRecordSource;
  snapshotSecured: boolean;
  overwritten: boolean;
  overwritableUntil: string;
};

export type TagDefinition = { name: string; level: number; label: string };

/** GET /api/v1/feeling/tags — 선택지·표시문의 정본 */
export type TagDefinitions = {
  temperature: TagDefinition[];
  humidity: TagDefinition[];
  wind: TagDefinition[];
};
