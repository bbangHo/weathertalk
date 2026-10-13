-- notification 모듈 (docs/to-be/06-notification.md §3). 좌표는 저장하지 않는다(ADR 0009). 다른 모듈 테이블은 참조하지 않고 ID만 둔다.

-- 기기 (§3.1, ADR 0008). 기기 키는 SHA-256 해시만 보관한다.
CREATE TABLE noti_device (
    id                uuid PRIMARY KEY,
    key_hash          varchar(64)  NOT NULL,
    platform          varchar(10)  NOT NULL,              -- IOS / ANDROID
    push_token        varchar(4096),                      -- 없을 수 있음(권한 요청 전)
    member_id         uuid,                               -- 연결 회원(없으면 비회원 기기)
    neighborhood_code varchar(10),                        -- 비회원 기기 현재 동네(FR-NOTI-05)
    guest_sensitivity varchar(20)  NOT NULL,              -- 알림용 민감도(비회원 기기, 기본 NORMAL)
    comment_enabled   boolean      NOT NULL,              -- 기기 단위 구독: 댓글 알림(회원 연결 중에만 의미)
    os_permission     varchar(10)  NOT NULL,              -- GRANTED / DENIED / UNKNOWN
    status            varchar(10)  NOT NULL,              -- ACTIVE / SUSPENDED
    suspended_reason  varchar(20),                        -- INVALID_TOKEN / STALE
    suspended_at      timestamp with time zone,
    registered_at     timestamp with time zone NOT NULL,
    last_confirmed_at timestamp with time zone NOT NULL,
    CONSTRAINT noti_device_platform_ck CHECK (platform IN ('IOS', 'ANDROID')),
    CONSTRAINT noti_device_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT noti_device_permission_ck CHECK (os_permission IN ('GRANTED', 'DENIED', 'UNKNOWN'))
);
-- 같은 푸시 토큰은 기기 하나에만(BR-NOTI-03)
CREATE UNIQUE INDEX noti_device_push_token_uk ON noti_device (push_token) WHERE push_token IS NOT NULL;
CREATE INDEX noti_device_member_idx ON noti_device (member_id) WHERE member_id IS NOT NULL;

-- 외출 시각 (FR-NOTI-17): 기기 단위 설정, 회원 연결·해제·탈퇴와 무관하게 남는다.
CREATE TABLE noti_departure_time (
    device_id      uuid NOT NULL REFERENCES noti_device (id) ON DELETE CASCADE,
    departure_time time NOT NULL,
    PRIMARY KEY (device_id, departure_time)
);

-- 동네별 알림 구독 (§3.2). 세 종류가 모두 꺼진 구독은 저장하지 않는다.
CREATE TABLE noti_subscription (
    id                             uuid PRIMARY KEY,
    device_id                      uuid        NOT NULL REFERENCES noti_device (id) ON DELETE CASCADE,
    target_type                    varchar(20) NOT NULL,   -- FOLLOW_CURRENT / FIXED
    neighborhood_code              varchar(10),            -- 지정 동네
    daily_enabled                  boolean     NOT NULL,
    precipitation_forecast_enabled boolean     NOT NULL,
    field_report_enabled           boolean     NOT NULL,
    consented_at                   timestamp with time zone,  -- 수신 동의 근거(OS 권한 허용 중 처음 켠 시각, ADR 0013)
    created_at                     timestamp with time zone NOT NULL,
    updated_at                     timestamp with time zone NOT NULL,
    CONSTRAINT noti_subscription_target_ck CHECK (
        (target_type = 'FIXED' AND neighborhood_code IS NOT NULL) OR (target_type = 'FOLLOW_CURRENT' AND neighborhood_code IS NULL)),
    CONSTRAINT noti_subscription_any_on_ck CHECK (daily_enabled OR precipitation_forecast_enabled OR field_report_enabled)
);
CREATE UNIQUE INDEX noti_subscription_follow_uk ON noti_subscription (device_id) WHERE target_type = 'FOLLOW_CURRENT';
CREATE UNIQUE INDEX noti_subscription_fixed_uk ON noti_subscription (device_id, neighborhood_code) WHERE target_type = 'FIXED';

-- 정기 알림 시각 (구독 1개당 0~notification.daily.max-times개)
CREATE TABLE noti_daily_time (
    subscription_id uuid NOT NULL REFERENCES noti_subscription (id) ON DELETE CASCADE,
    alert_time      time NOT NULL,
    PRIMARY KEY (subscription_id, alert_time)
);
CREATE INDEX noti_daily_time_time_idx ON noti_daily_time (alert_time);

-- 발송 기록 (§3.4). 발송 키 (종류, 회차 키, 실제 동네 또는 게시글, 기기)는 유일하다 — 다중 인스턴스·이벤트 재전달의 최종 중복 방지(BR-NOTI-10).
-- 회원 ID와 푸시 본문은 남기지 않는다. payload는 재시도·보류 중에만 들고 있다가 최종 상태가 되면 지운다.
CREATE TABLE noti_delivery (
    id              uuid PRIMARY KEY,
    kind            varchar(30) NOT NULL,   -- DAILY_WEATHER / PRECIPITATION_FORECAST / FIELD_REPORT_PRECIPITATION / COMMENT
    round_key       varchar(64) NOT NULL,   -- 정기: 회차 시각, 이벤트: 이벤트 ID, 댓글: 댓글 ID
    target_key      varchar(40) NOT NULL,   -- 동네 ID 또는 게시글 ID ('-' = 동네 미확인)
    device_id       uuid        NOT NULL REFERENCES noti_device (id) ON DELETE CASCADE,
    status          varchar(20) NOT NULL,   -- RESERVED / SUCCEEDED / RETRY_WAIT / HELD / FAILED / SKIPPED
    skip_reason     varchar(30),
    template_id     varchar(40),
    attempts        integer     NOT NULL,
    last_response   varchar(60),
    payload         text,
    next_attempt_at timestamp with time zone,
    deadline_at     timestamp with time zone,
    lease_until     timestamp with time zone,
    created_at      timestamp with time zone NOT NULL,
    updated_at      timestamp with time zone NOT NULL,
    CONSTRAINT noti_delivery_key_uk UNIQUE (kind, round_key, target_key, device_id)
);
CREATE INDEX noti_delivery_due_idx ON noti_delivery (next_attempt_at) WHERE status IN ('RETRY_WAIT', 'HELD');
CREATE INDEX noti_delivery_created_idx ON noti_delivery (created_at);

-- 쿨다운 (BR-NOTI-07): (종류, 동네 또는 게시글, 기기). 선점은 원자적 upsert, 최종 실패·생략이면 선점을 푼다.
CREATE TABLE noti_cooldown (
    kind       varchar(30) NOT NULL,
    target_key varchar(40) NOT NULL,
    device_id  uuid        NOT NULL REFERENCES noti_device (id) ON DELETE CASCADE,
    until      timestamp with time zone NOT NULL,
    holder     uuid        NOT NULL,   -- 선점한 발송 기록 ID
    PRIMARY KEY (kind, target_key, device_id)
);

-- 정기 날씨 알림 발송 회차 (FR-NOTI-09). 회차를 연 인스턴스만 계획한다(분산 락과 별개의 2차 장치).
CREATE TABLE noti_daily_round (
    round_at  timestamp with time zone PRIMARY KEY,
    opened_at timestamp with time zone NOT NULL,
    late      boolean NOT NULL          -- max-delay를 넘겨 열려 발송하지 않은 회차
);
