-- member 모듈 (docs/to-be/01-member.md §3)

-- 회원. 탈퇴 회원은 닉네임·이메일이 지워진 껍데기로 남는다(회원 ID 재사용 없음).
CREATE TABLE member_member (
    id                      uuid PRIMARY KEY,
    status                  varchar(20)  NOT NULL,              -- ACTIVE / WITHDRAWN
    nickname                varchar(30),                         -- 탈퇴 시 NULL (즉시 해제)
    nickname_changed_at     timestamp with time zone,            -- 회원이 직접 바꾼 시각. 무작위 부여는 세지 않는다
    temperature_sensitivity varchar(20)  NOT NULL,
    role                    varchar(20)  NOT NULL DEFAULT 'USER',-- USER / ADMIN
    email                   varchar(320),                        -- 선택 정보. 없으면 NULL ("null" 문자열 금지)
    joined_at               timestamp with time zone NOT NULL,
    withdrawn_at            timestamp with time zone,
    CONSTRAINT member_member_nickname_uk UNIQUE (nickname),
    CONSTRAINT member_member_status_ck CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT member_member_role_ck CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT member_member_email_ck CHECK (email IS NULL OR (email <> '' AND lower(email) <> 'null')),
    CONSTRAINT member_member_withdrawn_ck CHECK (status = 'ACTIVE' OR (nickname IS NULL AND email IS NULL))
);

-- 관리자 지정·해제는 운영 절차로만 한다(ADR 0012, FR-MEM-13). 앱·API 경로는 없다.
--   지정: UPDATE member_member SET role = 'ADMIN' WHERE id = '<회원 ID>' AND status = 'ACTIVE';
--   해제: UPDATE member_member SET role = 'USER'  WHERE id = '<회원 ID>';
-- 현재 요청자 해석은 요청마다 DB의 역할을 읽으므로 즉시 반영된다.

-- 로그인 계정 (제공자, 제공자 고유 ID). 탈퇴 시 삭제되어 같은 제공자 계정으로 즉시 재가입할 수 있다.
CREATE TABLE member_login_account (
    id               uuid PRIMARY KEY,
    member_id        uuid         NOT NULL REFERENCES member_member (id),
    provider         varchar(20)  NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    linked_at        timestamp with time zone NOT NULL,
    CONSTRAINT member_login_account_provider_uk UNIQUE (provider, provider_user_id),
    CONSTRAINT member_login_account_member_provider_uk UNIQUE (member_id, provider)
);

-- 가입 티켓. 원문은 앱에만 있고 서버는 해시만 보관한다(위조·재사용 방지, 1회 사용).
CREATE TABLE member_signup_ticket (
    token_hash       varchar(64)  PRIMARY KEY,
    provider         varchar(20)  NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    email            varchar(320),
    issued_at        timestamp with time zone NOT NULL,
    expires_at       timestamp with time zone NOT NULL,
    used_at          timestamp with time zone
);
CREATE INDEX member_signup_ticket_expires_idx ON member_signup_ticket (expires_at);

-- 로그인 세션. 갱신 토큰은 세션의 현재 세대만 유효하다(회전·재사용 탐지).
CREATE TABLE member_login_session (
    id                 uuid PRIMARY KEY,
    member_id          uuid        NOT NULL REFERENCES member_member (id),
    status             varchar(20) NOT NULL,          -- ACTIVE / REVOKED
    generation         integer     NOT NULL,
    started_at         timestamp with time zone NOT NULL,
    rotated_at         timestamp with time zone,      -- 마지막 회전 시각 (같은 세대 중복 요청 유예 판정)
    revoked_at         timestamp with time zone,
    CONSTRAINT member_login_session_status_ck CHECK (status IN ('ACTIVE', 'REVOKED'))
);
CREATE INDEX member_login_session_member_idx ON member_login_session (member_id);

-- 약관 버전. 종류별 현재 시행 버전 = 시행 시각이 지난 가장 높은 버전.
CREATE TABLE member_terms (
    kind           varchar(40)  NOT NULL,
    version        integer      NOT NULL,
    title          varchar(100) NOT NULL,
    required       boolean      NOT NULL,
    major_revision boolean      NOT NULL,              -- 중대 개정(필수 약관이면 기존 회원 재동의 필요)
    effective_at   timestamp with time zone NOT NULL,
    content_url    varchar(500) NOT NULL,
    PRIMARY KEY (kind, version)
);

-- 약관 동의 이력: (회원, 종류, 버전)당 1건 (BR-MEM-06).
CREATE TABLE member_terms_agreement (
    member_id uuid        NOT NULL REFERENCES member_member (id),
    kind      varchar(40) NOT NULL,
    version   integer     NOT NULL,
    agreed_at timestamp with time zone NOT NULL,
    PRIMARY KEY (member_id, kind, version),
    FOREIGN KEY (kind, version) REFERENCES member_terms (kind, version)
);
