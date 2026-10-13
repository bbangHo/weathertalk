-- 에어코리아 측정소 목록 (getMsrstnList 동기화). 동네 → 측정소 대응은 이 목록에서 PostGIS 최근접으로 매번 계산하므로
-- 외부 근접측정소 호출 결과를 담던 대응 표는 없앤다(에어코리아 개발계정 기능별 하루 500건 한도).
-- 좌표는 기술문서(측정소정보 v1.2, ver=1.1)의 dmX(경도)·dmY(위도) WGS84 값이다. 공공 시설 위치라 ADR 0009(사람 좌표 비저장)와 무관하다.
CREATE TABLE weather_air_station (
    station_name   varchar(40) PRIMARY KEY,           -- 에어코리아 측정소명(측정 조회의 식별자)
    address        varchar(510),
    network_name   varchar(20),                       -- 측정망(mangName): 도시대기, 도로변대기 등
    measured_items varchar(100),                      -- 측정 항목(item) 원문
    installed_year varchar(4),
    location       geography(Point, 4326),            -- 좌표가 없거나 이상하면 비우고 대응에서 뺀다
    synced_at      timestamp with time zone NOT NULL, -- 마지막으로 목록에서 확인된 동기화 시각
    closed_at      timestamp with time zone           -- 목록에서 빠진(폐지) 시각. 다시 나타나면 비운다
);

CREATE INDEX weather_air_station_location_gix ON weather_air_station USING gist (location) WHERE closed_at IS NULL;

DROP TABLE weather_air_station_mapping;
