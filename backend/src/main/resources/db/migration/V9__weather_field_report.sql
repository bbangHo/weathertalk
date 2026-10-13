-- weather 현장 제보·이벤트 발행 기록 (docs/to-be/03-weather.md §3.7·§3.9). 좌표는 저장하지 않는다(ADR 0009).

-- 현장 제보. 회원 ID는 탈퇴 시 비운다(§6.4). 같은 회원·동네·축 min-interval 안의 새 제보는 이전 제보를 대체한다:
-- 이전 행은 replaced_at을 채워 집계에서 빼고 하루 한도 계산에는 남긴다(취소한 제보도 마찬가지, FR-WEA-14 규칙 6).
CREATE TABLE weather_field_report (
    id                uuid PRIMARY KEY,
    neighborhood_code varchar(10) NOT NULL,
    member_id         uuid,
    axis              varchar(20) NOT NULL,
    kind              varchar(20) NOT NULL,
    reported_at       timestamp with time zone NOT NULL,
    replaced_at       timestamp with time zone,
    canceled_at       timestamp with time zone
);

-- 현황 집계·기준 도달 판정: 동네 + 축 + 제보 시각 창
CREATE INDEX weather_field_report_neighborhood_idx ON weather_field_report (neighborhood_code, axis, reported_at);
-- 대체 대상·하루 한도
CREATE INDEX weather_field_report_member_idx ON weather_field_report (member_id, reported_at) WHERE member_id IS NOT NULL;
-- 보존 기간 정리
CREATE INDEX weather_field_report_reported_idx ON weather_field_report (reported_at);

-- 현장 제보 강수 발행 기록: (동네, 강수 형태)별 마지막 현장제보강수기준도달함 발행 시각 (BR-WEA-17 재발행 억제)
CREATE TABLE weather_field_report_precip_publication (
    neighborhood_code  varchar(10) NOT NULL,
    precipitation_type varchar(12) NOT NULL,
    last_published_at  timestamp with time zone NOT NULL,
    reporter_count     integer NOT NULL,
    PRIMARY KEY (neighborhood_code, precipitation_type)
);

-- 강수 구간 발행 기록: 동네별로 발행한 강수 구간 (BR-WEA-20 중복 방지)
CREATE TABLE weather_precip_episode (
    neighborhood_code varchar(10) NOT NULL,
    expected_start_at timestamp with time zone NOT NULL,
    published_at      timestamp with time zone NOT NULL,
    basis_issued_at   timestamp with time zone NOT NULL,
    -- 구간 시작 후 마지막으로 강수가 확인된 시각(dry-gap 판정용)
    last_wet_at       timestamp with time zone,
    PRIMARY KEY (neighborhood_code, expected_start_at)
);
