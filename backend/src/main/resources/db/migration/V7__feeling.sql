-- feeling 모듈 (docs/to-be/04-feeling.md §3.2·§3.3). 체감 기록은 게시글과 독립된 데이터셋이다(ADR 0004).
-- 좌표는 저장하지 않는다(ADR 0009). 회원 ID는 탈퇴 시 비우고 나머지는 익명으로 보존한다(FR-FEL-09).

CREATE TABLE feeling_record (
    id                          uuid PRIMARY KEY,
    member_id                   uuid,
    neighborhood_code           varchar(10) NOT NULL,
    -- 생성 시각: 덮어쓰기 간격 판정 기준, 덮어써도 바뀌지 않는다(BR-FEL-04)
    created_at                  timestamp with time zone NOT NULL,
    -- 기록 시각: 마지막으로 내용이 정해진 시각, 집계 시간 창 기준
    recorded_at                 timestamp with time zone NOT NULL,
    temperature_level           smallint NOT NULL CHECK (temperature_level BETWEEN 1 AND 7),
    humidity_level              smallint CHECK (humidity_level BETWEEN 1 AND 5),
    wind_level                  smallint CHECK (wind_level BETWEEN 1 AND 3),
    -- 기록 시점 온도 민감도(복사본)
    temperature_sensitivity     varchar(20) NOT NULL,
    source                      varchar(30) NOT NULL,
    -- 피드백 정보: 출처가 FEEDBACK·DIRECT_WITH_FEEDBACK일 때만
    feedback_answer             varchar(10),
    feedback_shown_level        smallint CHECK (feedback_shown_level BETWEEN 1 AND 7),
    feedback_shown_source       varchar(30),
    feedback_shown_mapping_version integer,
    -- 관측 스냅샷(§3.3): 확보 실패 시 status = MISSING, 값은 비어 있을 수 있다
    snapshot_status             varchar(10) NOT NULL,
    snapshot_observed_at        timestamp with time zone,
    snapshot_temperature        double precision,
    snapshot_humidity           double precision,
    snapshot_wind_speed         double precision,
    snapshot_pm10               double precision,
    snapshot_pm25               double precision,
    snapshot_precipitation_type varchar(12),
    -- 항목별 출처(weather ValueSource: OBSERVATION/FORECAST/MEASUREMENT)
    snapshot_temperature_source varchar(12),
    snapshot_humidity_source    varchar(12),
    snapshot_wind_speed_source  varchar(12),
    snapshot_air_source         varchar(12),
    CONSTRAINT feeling_record_feedback_info CHECK (
        (source = 'DIRECT' AND feedback_answer IS NULL)
        OR (source <> 'DIRECT' AND feedback_answer IS NOT NULL AND feedback_shown_level IS NOT NULL))
);

-- 동네 체감 집계: 동네 + 기록 시각 창
CREATE INDEX feeling_record_neighborhood_recorded_idx ON feeling_record (neighborhood_code, recorded_at);
-- 덮어쓰기 대상·최근 기록·마이페이지 목록
CREATE INDEX feeling_record_member_idx ON feeling_record (member_id, neighborhood_code, created_at)
    WHERE member_id IS NOT NULL;
CREATE INDEX feeling_record_member_recorded_idx ON feeling_record (member_id, recorded_at)
    WHERE member_id IS NOT NULL;
