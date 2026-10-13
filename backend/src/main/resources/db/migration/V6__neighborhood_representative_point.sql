-- 동네 판정을 기상청 행정동 대표점 기준으로 바꾼다 (ADR 0016).
-- 기상청 자료에는 경계가 없으므로 경계는 선택 값이 되고, 경계가 없는 동네는 가장 가까운 대표점으로 판정한다.

ALTER TABLE nbr_neighborhood ALTER COLUMN boundary DROP NOT NULL;

-- 최근접(KNN `<->`)·거리 판정용. 미터 단위 거리를 바로 쓰도록 geography로 둔다.
ALTER TABLE nbr_neighborhood ADD COLUMN representative_point geography(Point, 4326);
UPDATE nbr_neighborhood
SET representative_point = ST_SetSRID(ST_MakePoint(representative_lon, representative_lat), 4326)::geography;
ALTER TABLE nbr_neighborhood ALTER COLUMN representative_point SET NOT NULL;

CREATE INDEX nbr_neighborhood_representative_gix ON nbr_neighborhood USING gist (representative_point);
