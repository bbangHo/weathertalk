// 동네 응답 형식 (backend neighborhood/internal/web/NeighborhoodResponse, MyNeighborhoodController)

export type NeighborhoodStatus = 'VALID' | 'RETIRED';

/** 백엔드 NeighborhoodResponse 그대로. representativePoint는 동네 대표점(사람 위치 아님)이지만 앱은 쓰지 않는다. */
export type NeighborhoodResponse = {
  id: string;
  sidoName: string;
  sigunguName: string;
  eupmyeondongName: string;
  fullName: string;
  shortName: string;
  representativePoint: { latitude: number; longitude: number } | null;
  forecastGrid: { nx: number; ny: number } | null;
  status: NeighborhoodStatus;
};

/** 화면이 쓰는 동네 정보. 좌표 필드를 떼어 내 캐시·저장 어디에도 좌표가 섞이지 않게 한다. */
export type NeighborhoodSummary = {
  id: string;
  sidoName: string;
  sigunguName: string;
  eupmyeondongName: string;
  fullName: string;
  shortName: string;
};

/** 백엔드 MyNeighborhoodController.CurrentNeighborhoodResponse. 현재 동네가 없으면 neighborhood=null(정상). */
export type CurrentNeighborhoodResponse = {
  neighborhood: NeighborhoodResponse | null;
  confirmedAt: string | null;
  refreshed: boolean | null;
};

/** 백엔드 MyNeighborhoodController.MergeResponse */
export type MergeResponse = {
  interests: NeighborhoodResponse[];
  /** 이미 등록됨·폐지·없는 동네라 건너뛴 ID */
  skipped: string[];
  /** 최대 개수를 넘어 버린 ID */
  discarded: string[];
};

/** 백엔드 LocationRequest. 좌표는 본문으로만 보낸다(ADR 0009). */
export type LocationRequest = {
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
};

export function toSummary(response: NeighborhoodResponse): NeighborhoodSummary {
  return {
    id: response.id,
    sidoName: response.sidoName,
    sigunguName: response.sigunguName,
    eupmyeondongName: response.eupmyeondongName,
    fullName: response.fullName,
    shortName: response.shortName,
  };
}
