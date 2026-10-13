-- member 모듈 M7: 소셜 로그인 제공자 연결 해제용 자격, 탈퇴·계정 해제 후 제공자 연결 해제 작업 (docs/to-be/01-member.md §3.2, FR-MEM-07·09)

-- 제공자 연결 해제용 자격(애플·구글 갱신 토큰). 암호화(AES-GCM, 키는 MEMBER_PROVIDER_TOKEN_KEY)해 보관하며 원문은 남기지 않는다.
-- 카카오는 어드민 키 + 회원번호로 해제하므로 비어 있다.
ALTER TABLE member_login_account ADD COLUMN unlink_credential text;
ALTER TABLE member_login_account ADD COLUMN unlink_credential_updated_at timestamp with time zone;

-- 처음 보는 로그인 계정으로 로그인했을 때 받은 자격을 가입 완료까지 들고 간다(가입 티켓과 같은 수명).
ALTER TABLE member_signup_ticket ADD COLUMN unlink_credential text;

-- 제공자 연결 해제 작업 (FR-MEM-09 규칙 3~5). 탈퇴·로그인 계정 해제 트랜잭션에서 기록하고, 커밋 후 비동기로 실행·재시도한다.
-- 회원 ID는 남기지 않는다(탈퇴 후 추적 불가). 실행 직전 같은 로그인 계정이 다시 연결돼 있으면 취소한다.
CREATE TABLE member_external_unlink (
    id               uuid PRIMARY KEY,
    provider         varchar(20)  NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    reason           varchar(20)  NOT NULL,              -- WITHDRAWAL / ACCOUNT_UNLINK
    credential       text,                               -- 암호화된 연결 해제용 자격. 성공·취소 즉시, 실패는 retention 뒤 파기
    status           varchar(20)  NOT NULL,              -- PENDING / SUCCEEDED / FAILED / CANCELLED / SKIPPED
    attempts         integer      NOT NULL,
    next_attempt_at  timestamp with time zone NOT NULL,
    lease_until      timestamp with time zone,           -- 실행 중 점유(다중 인스턴스 중복 호출 방지)
    last_error       varchar(500),
    created_at       timestamp with time zone NOT NULL,
    completed_at     timestamp with time zone,
    CONSTRAINT member_external_unlink_status_ck CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT member_external_unlink_reason_ck CHECK (reason IN ('WITHDRAWAL', 'ACCOUNT_UNLINK'))
);
CREATE INDEX member_external_unlink_due_idx ON member_external_unlink (next_attempt_at) WHERE status = 'PENDING';
