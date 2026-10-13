-- feeling 체감 매핑·민감도 보정 제안 (docs/to-be/04-feeling.md §3.5·§3.6, FR-FEL-06~08).

-- 매핑 버전: 한 번의 재계산 결과. 만들어진 뒤 셀은 바뀌지 않는다(불변식 6).
-- 상태: CREATED → APPLIED(적용 중) → ARCHIVED(보관) → DISCARDED(폐기, 셀 삭제) / CREATED → REJECTED(거부)
CREATE TABLE feeling_mapping_version (
    version         integer PRIMARY KEY,
    status          varchar(10) NOT NULL,
    created_at      timestamp with time zone NOT NULL,
    -- 입력 기간: 기록 시각이 (input_from, input_to]
    input_from      timestamp with time zone NOT NULL,
    input_to        timestamp with time zone NOT NULL,
    -- 사용한 설정값(계절 구분·구간 정의·최소 표본 수·피드백 가중치). 조회 때 구간은 이 값으로 정한다
    settings        jsonb NOT NULL,
    -- 검증 결과(BR-FEL-15): 채운 셀 수, 단조성 위반으로 비운 축약 셀, 거부 사유
    validation      jsonb NOT NULL,
    -- 적중률(FR-FEL-06 규칙 5): 직전 실행 이후 피드백의 산출 출처·매핑 버전별 응답 분포
    hit_rate        jsonb NOT NULL,
    applied_at      timestamp with time zone,
    archived_at     timestamp with time zone
);

-- 버전 번호는 지운 버전과도 겹치지 않게 시퀀스로 준다(번호로 셀을 캐시하므로)
CREATE SEQUENCE feeling_mapping_version_seq;

-- 적용 중인 버전은 최대 하나(불변식 6)
CREATE UNIQUE INDEX feeling_mapping_version_applied_uq ON feeling_mapping_version (status) WHERE status = 'APPLIED';

-- 매핑 셀(세 층). DETAIL = 계절·기온·습도·풍속, NO_SEASON = 기온·습도·풍속, COARSE = 기온만
CREATE TABLE feeling_mapping_cell (
    version                 integer NOT NULL REFERENCES feeling_mapping_version (version) ON DELETE CASCADE,
    layer                   varchar(10) NOT NULL,
    temperature_sensitivity varchar(20) NOT NULL,
    season                  varchar(10),
    temperature_bin         smallint NOT NULL,
    humidity_bin            smallint,
    wind_bin                smallint,
    temperature_level       smallint NOT NULL CHECK (temperature_level BETWEEN 1 AND 7),
    humidity_level          smallint CHECK (humidity_level BETWEEN 1 AND 5),
    wind_level              smallint CHECK (wind_level BETWEEN 1 AND 3),
    -- 근거 표본 수(가중 전 건수)와 온도 범주 가중 합
    sample_count            integer NOT NULL,
    weighted_count          double precision NOT NULL
);

CREATE INDEX feeling_mapping_cell_version_idx ON feeling_mapping_cell (version);

-- 매핑 입력(스냅샷 확보 기록, 기록 시각 범위)과 백필 대상(미확보, 기록 시각 범위)
CREATE INDEX feeling_record_snapshot_recorded_idx ON feeling_record (snapshot_status, recorded_at);

-- 민감도 보정 제안. 상태: OFFERED(제안됨) → ACCEPTED / DECLINED / EXPIRED / INVALIDATED(무효)
CREATE TABLE feeling_calibration_offer (
    id                      uuid PRIMARY KEY,
    member_id               uuid NOT NULL,
    current_sensitivity     varchar(20) NOT NULL,
    suggested_sensitivity   varchar(20) NOT NULL,
    -- 근거: 판정에 쓴 기록 수(가중 전·후)와 그룹별 가중 평균 오차, 예상 단계에 쓴 매핑 버전
    record_count            integer NOT NULL,
    weighted_record_count   double precision NOT NULL,
    error_heat_sensitive    double precision NOT NULL,
    error_normal            double precision NOT NULL,
    error_cold_sensitive    double precision NOT NULL,
    mapping_version         integer NOT NULL,
    judged_at               timestamp with time zone NOT NULL,
    expires_at              timestamp with time zone NOT NULL,
    status                  varchar(12) NOT NULL,
    responded_at            timestamp with time zone
);

-- 회원당 제안됨 상태는 최대 하나(불변식 7)
CREATE UNIQUE INDEX feeling_calibration_offer_offered_uq ON feeling_calibration_offer (member_id)
    WHERE status = 'OFFERED';
CREATE INDEX feeling_calibration_offer_member_idx ON feeling_calibration_offer (member_id, judged_at);

-- 보정 제안 빈도 제한 기준 시각(BR-FEL-17 규칙 6)
CREATE TABLE feeling_calibration_member (
    member_id                   uuid PRIMARY KEY,
    last_judged_at              timestamp with time zone,
    last_declined_at            timestamp with time zone,
    last_sensitivity_changed_at timestamp with time zone
);
