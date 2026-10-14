// 마이페이지 API (01 FR-MEM-07·09·10·11, 04 FR-FEL-08·11, backend member/internal/web/MemberController·AuthController,
// feeling/internal/web/FeelingController·CalibrationOfferController, feeling/MyFeelingRecords)
import type { ApiClient } from '@/api/client';
import type { AuthProvider, MyProfile } from '@/auth/api';
import type {
  CalibrationOffer,
  FeelingRecordSource,
  HumidityTagName,
  TemperatureTagName,
  WindTagName,
} from '@/feeling/types';
import type { TemperatureSensitivity } from '@/storage';

/** 백엔드 MemberAccountService.NicknameChanged */
export type NicknameChanged = { nickname: string; nextChangeAvailableAt: string | null };

export function changeNickname(api: ApiClient, nickname: string): Promise<NicknameChanged> {
  return api.put<NicknameChanged>('/api/v1/members/me/nickname', { nickname });
}

/** 로그인 계정 해제. 마지막 계정이면 LAST_LOGIN_ACCOUNT로 거부된다. */
export function unlinkLoginAccount(api: ApiClient, provider: AuthProvider | 'DEV'): Promise<MyProfile> {
  return api.delete<MyProfile>(`/api/v1/members/me/login-accounts/${provider}`);
}

/**
 * 로그인 세션 폐기. 접근 토큰이 만료됐어도 갱신 토큰으로 폐기되도록 본문에 싣고, 재발급 시도를 하지 않게 auth:false로 보낸다.
 */
export function logout(api: ApiClient, refreshToken: string | null): Promise<void> {
  return api.post<void>('/api/v1/auth/logout', { refreshToken }, { auth: false });
}

/** 탈퇴. 서버가 제공자 연결 해제를 커밋 후 비동기로 하므로 앱은 제공자 재인증을 하지 않는다. */
export function withdraw(api: ApiClient): Promise<void> {
  return api.delete<void>('/api/v1/members/me');
}

export function getCalibrationOffer(api: ApiClient): Promise<{ offer: CalibrationOffer | null }> {
  return api.get<{ offer: CalibrationOffer | null }>('/api/v1/members/me/calibration-offer');
}

export type FeelingTendency = 'FEELS_COLDER' | 'SIMILAR' | 'FEELS_HOTTER' | 'INSUFFICIENT_RECORDS';

/** 백엔드 MyFeelingRecords.Item. 식별자는 { value } 형태로 직렬화된다. */
export type MyFeelingRecordItem = {
  id: { value: string };
  recordedAt: string;
  neighborhoodId: { value: string };
  neighborhoodName: string | null;
  temperatureTag: TemperatureTagName;
  humidityTag: HumidityTagName | null;
  windTag: WindTagName | null;
  source: FeelingRecordSource;
  temperatureSensitivity: TemperatureSensitivity;
};

export type MyFeelingPattern = {
  recordCount: number;
  temperatureDistribution: Partial<Record<TemperatureTagName, number>>;
  tendency: FeelingTendency;
  tendencyPhrase: string | null;
  averageDifference: number | null;
  tendencySampleCount: number;
};

export type MyFeelingRecords = { records: MyFeelingRecordItem[]; pattern: MyFeelingPattern };

export function getMyFeelingRecords(api: ApiClient): Promise<MyFeelingRecords> {
  return api.get<MyFeelingRecords>('/api/v1/members/me/feeling-records');
}
