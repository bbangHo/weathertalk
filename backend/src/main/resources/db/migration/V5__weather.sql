-- weather 모듈 (docs/to-be/03-weather.md §3). 수집 결과는 수집 단위(예보 격자·중기 구역·측정소·자외선 구역)로 저장하고,
-- 조회·판정이 하나의 사본을 함께 쓴다(ADR 0005). 같은 수집 단위·발표 시각의 결과는 통째로 교체한다(부분 갱신 없음).
-- 신선도는 저장하지 않는다(조회 시점에 발표 시각으로 판정). 좌표는 저장하지 않는다(ADR 0009).

-- 수집 상태: 수집 단위 × 데이터 종류마다 1건 (03 §3.2)
CREATE TABLE weather_collect_state (
    kind                   varchar(20) NOT NULL,
    unit_key               varchar(40) NOT NULL,
    last_success_issued_at timestamp with time zone,
    last_attempt_at        timestamp with time zone,
    last_attempt_target    timestamp with time zone,
    last_result            varchar(20),
    consecutive_failures   integer NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, unit_key)
);

-- 초단기실황(현재 관측). 과거 관측 조회를 위해 weather.retention.observation 동안 보관
CREATE TABLE weather_observation (
    grid_nx                integer NOT NULL,
    grid_ny                integer NOT NULL,
    observed_at            timestamp with time zone NOT NULL,
    temperature            double precision,
    humidity               double precision,
    wind_speed             double precision,
    precip_lower           double precision,
    precip_upper           double precision,
    precip_lower_exclusive boolean,
    precipitation_type     varchar(12),
    collected_at           timestamp with time zone NOT NULL,
    PRIMARY KEY (grid_nx, grid_ny, observed_at)
);

-- 단기예보 발표분
CREATE TABLE weather_short_term_issue (
    grid_nx      integer NOT NULL,
    grid_ny      integer NOT NULL,
    issued_at    timestamp with time zone NOT NULL,
    collected_at timestamp with time zone NOT NULL,
    PRIMARY KEY (grid_nx, grid_ny, issued_at)
);

-- 단기예보 칸: (격자, 발표 시각, 예보 시각[날짜+시각]) 하나에 한 행
CREATE TABLE weather_short_term_slot (
    grid_nx                integer NOT NULL,
    grid_ny                integer NOT NULL,
    issued_at              timestamp with time zone NOT NULL,
    forecast_at            timestamp with time zone NOT NULL,
    temperature            double precision,
    humidity               double precision,
    wind_speed             double precision,
    precipitation_probability integer,
    precipitation_type     varchar(12),
    precip_lower           double precision,
    precip_upper           double precision,
    precip_lower_exclusive boolean,
    snow_lower             double precision,
    snow_upper             double precision,
    snow_lower_exclusive   boolean,
    sky                    varchar(16),
    daily_max              double precision,
    daily_min              double precision,
    PRIMARY KEY (grid_nx, grid_ny, issued_at, forecast_at),
    FOREIGN KEY (grid_nx, grid_ny, issued_at) REFERENCES weather_short_term_issue (grid_nx, grid_ny, issued_at) ON DELETE CASCADE
);

-- 중기 육상예보(구역별)
CREATE TABLE weather_mid_land (
    region_id          varchar(12) NOT NULL,
    issued_at          timestamp with time zone NOT NULL,
    forecast_date      date NOT NULL,
    am_sky             varchar(16),
    pm_sky             varchar(16),
    am_precipitation_probability integer,
    pm_precipitation_probability integer,
    am_precipitation_type varchar(12),
    pm_precipitation_type varchar(12),
    collected_at       timestamp with time zone NOT NULL,
    PRIMARY KEY (region_id, issued_at, forecast_date)
);

-- 중기 기온(지점별)
CREATE TABLE weather_mid_temperature (
    region_id     varchar(12) NOT NULL,
    issued_at     timestamp with time zone NOT NULL,
    forecast_date date NOT NULL,
    min_temperature double precision,
    max_temperature double precision,
    collected_at  timestamp with time zone NOT NULL,
    PRIMARY KEY (region_id, issued_at, forecast_date)
);

-- 대기질 측정(측정소별)
CREATE TABLE weather_air_measurement (
    station_name varchar(40) NOT NULL,
    measured_at  timestamp with time zone NOT NULL,
    pm10         double precision,
    pm25         double precision,
    pm10_grade   varchar(12),
    pm25_grade   varchar(12),
    ozone_grade  varchar(12),
    collected_at timestamp with time zone NOT NULL,
    PRIMARY KEY (station_name, measured_at)
);

-- 동네 → 가까운 순 측정소 대응(weather.air.station-mapping-ttl마다 다시 정함). 동네 ID만 둔다(다른 모듈 테이블 참조 없음)
CREATE TABLE weather_air_station_mapping (
    neighborhood_code varchar(10) PRIMARY KEY,
    station_names     varchar(400) NOT NULL,   -- 가까운 순, 쉼표 구분
    mapped_at         timestamp with time zone NOT NULL
);

-- 자외선 지수(생활기상지수 행정구역별)
CREATE TABLE weather_uv_index (
    area_no      varchar(10) NOT NULL,
    issued_at    timestamp with time zone NOT NULL,
    forecast_at  timestamp with time zone NOT NULL,
    uv_value     integer,
    collected_at timestamp with time zone NOT NULL,
    PRIMARY KEY (area_no, issued_at, forecast_at)
);
