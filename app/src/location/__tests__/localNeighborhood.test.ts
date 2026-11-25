// 기기 안 동네 판정: 행정동 대표점 최근접, 최대 거리(20km) 밖이면 판정 불가 (서버 판정과 같은 규칙, ADR 0009·0016)
import { overrideAppParams, resetAppParams } from '@/config/appParams';

import { distanceMeters, locateLocally } from '../localNeighborhood';

afterEach(() => resetAppParams());

describe('기기 안 동네 판정', () => {
  it('알려진 좌표는 가장 가까운 대표점의 동네로 판정한다', () => {
    // 청운효자동 대표점(37.5841367, 126.9706519)에서 약 60m
    expect(locateLocally(37.5845, 126.9702)).toEqual({
      id: '1111051500',
      sidoName: '서울특별시',
      sigunguName: '종로구',
      eupmyeondongName: '청운효자동',
      fullName: '서울특별시 종로구 청운효자동',
      shortName: '종로구 청운효자동',
    });
    expect(locateLocally(37.5007, 127.0365)?.id).toBe('1168064000'); // 역삼1동
  });

  it('바다 위(가장 가까운 대표점도 최대 거리 밖)·잘못된 좌표는 null', () => {
    expect(locateLocally(36.0, 124.0)).toBeNull(); // 서해 먼바다
    expect(locateLocally(Number.NaN, 127.0)).toBeNull();
    expect(locateLocally(91, 127.0)).toBeNull();
  });

  it('최대 거리는 앱 설정을 따른다', () => {
    // 역삼1동 대표점(37.4925833, 127.0351444)에서 약 900m
    overrideAppParams({ maxNearestDistanceMeters: 500 });
    expect(locateLocally(37.5007, 127.0365)).toBeNull();
  });

  it('하버사인 거리: 위도 0.1°는 약 11.1km', () => {
    expect(distanceMeters(35.0, 129.0, 35.1, 129.0)).toBeCloseTo(11119.5, 0);
  });
});
