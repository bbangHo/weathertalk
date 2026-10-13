-- neighborhood 모듈 (docs/to-be/02-neighborhood.md §3). 좌표는 어디에도 저장하지 않는다(ADR 0009).

-- 동네 기준 데이터 적재 이력 (버전 단위 통째 적재)
CREATE TABLE nbr_dataset_version (
    version       varchar(40) PRIMARY KEY,
    loaded_at     timestamp with time zone NOT NULL,
    total_count   integer NOT NULL,
    added_count   integer NOT NULL,
    changed_count integer NOT NULL,
    retired_count integer NOT NULL
);

-- 동네(행정동). 동네 ID = 행정동 코드(shared Ids.NeighborhoodId). 런타임 요청으로 생기지 않고 적재로만 생긴다.
CREATE TABLE nbr_neighborhood (
    code               varchar(10)  PRIMARY KEY,
    sido_name          varchar(40)  NOT NULL,
    sigungu_name       varchar(40)  NOT NULL,
    eupmyeondong_name  varchar(40)  NOT NULL,
    boundary           geometry(MultiPolygon, 4326) NOT NULL,
    representative_lat double precision NOT NULL,     -- 경계의 대표점(사람의 위치 아님)
    representative_lon double precision NOT NULL,
    grid_nx            integer NOT NULL,             -- 기상청 단기예보 격자
    grid_ny            integer NOT NULL,
    status             varchar(10)  NOT NULL,        -- VALID / RETIRED
    dataset_version    varchar(40)  NOT NULL REFERENCES nbr_dataset_version (version),
    CONSTRAINT nbr_neighborhood_status_ck CHECK (status IN ('VALID', 'RETIRED'))
);
CREATE INDEX nbr_neighborhood_boundary_gix ON nbr_neighborhood USING gist (boundary);
CREATE INDEX nbr_neighborhood_grid_idx ON nbr_neighborhood (grid_nx, grid_ny);

-- 회원의 현재 동네: 회원당 0~1행, 덮어쓰기(좌표·이동 이력 없음)
CREATE TABLE nbr_current_neighborhood (
    member_id         uuid PRIMARY KEY,
    neighborhood_code varchar(10) NOT NULL REFERENCES nbr_neighborhood (code),
    confirmed_at      timestamp with time zone NOT NULL
);
CREATE INDEX nbr_current_neighborhood_confirmed_idx ON nbr_current_neighborhood (confirmed_at);

-- 관심 동네: 회원당 최대 neighborhood.max-interest 개, 중복 없음
CREATE TABLE nbr_interest_neighborhood (
    member_id         uuid        NOT NULL,
    neighborhood_code varchar(10) NOT NULL REFERENCES nbr_neighborhood (code),
    position          integer     NOT NULL,
    registered_at     timestamp with time zone NOT NULL,
    PRIMARY KEY (member_id, neighborhood_code)
);
