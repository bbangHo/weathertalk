// 기기 안 동네 판정 (02 FR-NBR-01, ADR 0009·0016). 좌표를 서버로 보내지 않고 앱에 넣은 행정동 대표점 중 가장 가까운 곳을 고른다.
// 서버 판정과 같은 규칙: 유효 동네 대표점 최근접, 최대 거리 밖(바다 위·국외)이면 판정 불가, 거리가 같으면 코드가 작은 쪽.
// 대표점 데이터는 generateNeighborhoodPoints.mjs로 서버 기준 데이터(기상청 행정동 CSV)에서 만든다.
import { getAppParams } from '@/config/appParams';
import type { NeighborhoodSummary } from '@/neighborhood/types';

import points from './neighborhoodPoints.json';

/** 기기에서 받은 좌표. 판정 함수 안에서만 쓰고 저장·로그·전송하지 않는다. */
export type DeviceCoords = {
  latitude: number;
  longitude: number;
  /** 위치 정확도(미터, 작을수록 정확). 모르면 null */
  accuracyMeters: number | null;
};

type Row = [code: string, sigungu: number, eupmyeondong: string, latE5: number, lonE5: number];

const data = points as unknown as { sido: string[]; sigungu: [number, string][]; rows: Row[] };

const EARTH_RADIUS_M = 6371008.8;
const rad = (deg: number) => (deg * Math.PI) / 180;

/** 두 점 사이 거리(미터, 하버사인). */
export function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const a =
    Math.sin(rad(lat2 - lat1) / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lon2 - lon1) / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

function toSummary([code, sigunguIndex, eupmyeondongName]: Row): NeighborhoodSummary {
  const [sidoIndex, sigunguName] = data.sigungu[sigunguIndex];
  const sidoName = data.sido[sidoIndex];
  return {
    id: code,
    sidoName,
    sigunguName,
    eupmyeondongName,
    fullName: `${sidoName} ${sigunguName} ${eupmyeondongName}`,
    shortName: `${sigunguName} ${eupmyeondongName}`,
  };
}

/**
 * 좌표가 속한 동네: 가장 가까운 대표점의 동네. 최대 거리(neighborhood.max-nearest-distance)보다 멀거나 좌표가 잘못됐으면 null.
 * shortcut: 3,564개 전수 비교(기기에서 1ms 안팎), 동네 수가 크게 늘면 격자 색인으로 바꾼다.
 */
export function locateLocally(latitude: number, longitude: number): NeighborhoodSummary | null {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
    return null;
  }
  let best: Row | null = null;
  let bestCm = Infinity;
  for (const row of data.rows) {
    // cm 단위로 반올림해 비교한다(서버와 같은 동률 처리). rows가 코드 순이라 동률이면 먼저 나온(코드가 작은) 쪽이 남는다.
    const cm = Math.round(distanceMeters(latitude, longitude, row[3] / 1e5, row[4] / 1e5) * 100);
    if (cm < bestCm) {
      best = row;
      bestCm = cm;
    }
  }
  return best && bestCm <= getAppParams().maxNearestDistanceMeters * 100 ? toSummary(best) : null;
}
