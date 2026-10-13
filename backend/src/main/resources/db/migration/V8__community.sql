-- community 모듈 (docs/to-be/05-community.md §3). 다른 모듈의 데이터는 ID로만 참조한다(ADR 0010).
-- 게시글·댓글·좋아요는 물리 삭제하고 숨김 상태를 두지 않는다(ADR 0003, 0012). 좌표는 저장하지 않는다(ADR 0009).

CREATE TABLE community_post (
    id                  uuid PRIMARY KEY,
    author_id           uuid NOT NULL,
    -- 게시글의 동네: 작성 직전 현재 동네 확정 결과, 이후 바뀌지 않는다(BR-COM-01)
    neighborhood_code   varchar(10) NOT NULL,
    -- 길이 제한은 운영 파라미터(community.post.*)라 DB에 두지 않는다
    body                text NOT NULL,
    feeling_record_id   uuid,
    created_at          timestamp with time zone NOT NULL,
    updated_at          timestamp with time zone
);

-- 피드: 동네 + (작성 시각, ID) 내림차순 커서(BR-COM-08). 인기글 후보도 같은 인덱스로 찾는다.
CREATE INDEX community_post_feed_idx ON community_post (neighborhood_code, created_at DESC, id DESC);
-- 내가 쓴 글, 탈퇴 정리
CREATE INDEX community_post_author_idx ON community_post (author_id, created_at DESC, id DESC);

CREATE TABLE community_comment (
    id          uuid PRIMARY KEY,
    post_id     uuid NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    -- 없으면 댓글, 있으면 답글. 상위는 항상 댓글이다(깊이 1) — 서비스가 검증한다
    parent_id   uuid REFERENCES community_comment (id) ON DELETE CASCADE,
    -- 삭제된 댓글 자리(DELETED)는 작성자·본문이 없다
    author_id   uuid,
    body        text,
    status      varchar(10) NOT NULL,
    created_at  timestamp with time zone NOT NULL,
    updated_at  timestamp with time zone,
    CONSTRAINT community_comment_status_ck CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT community_comment_deleted_ck CHECK (
        (status = 'ACTIVE' AND author_id IS NOT NULL AND body IS NOT NULL)
        OR (status = 'DELETED' AND author_id IS NULL AND body IS NULL AND parent_id IS NULL))
);

-- 댓글 목록: 게시글의 댓글을 (작성 시각, ID) 오름차순 커서로, 답글은 상위 댓글별로
CREATE INDEX community_comment_post_idx ON community_comment (post_id, created_at, id) WHERE parent_id IS NULL;
CREATE INDEX community_comment_parent_idx ON community_comment (parent_id, created_at, id) WHERE parent_id IS NOT NULL;
CREATE INDEX community_comment_author_idx ON community_comment (author_id) WHERE author_id IS NOT NULL;

-- 좋아요: (게시글, 회원)당 1건을 저장소가 보장한다(BR-COM-11). 레코드를 지워도 게시글·회원에는 영향이 없다(legacy 03-#9).
CREATE TABLE community_like (
    post_id     uuid NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    member_id   uuid NOT NULL,
    created_at  timestamp with time zone NOT NULL,
    PRIMARY KEY (post_id, member_id)
);

CREATE INDEX community_like_member_idx ON community_like (member_id);

-- 게시글 이미지 (§3.2). 상태: UPLOADING(사전 서명 발급, 업로드 확인 전) → UNATTACHED(미첨부) → ATTACHED → DISPOSABLE(정리 대상)
-- 파일은 객체 저장소에 있고 여기에는 키만 둔다. 정리 배치가 파일을 지운 뒤 행을 지운다(FR-COM-14).
CREATE TABLE community_post_image (
    id              uuid PRIMARY KEY,
    uploader_id     uuid NOT NULL,
    post_id         uuid REFERENCES community_post (id) ON DELETE SET NULL,
    position        smallint,
    status          varchar(12) NOT NULL,
    -- 앱이 원본을 올리는 임시 키. 업로드 확인(메타데이터 제거) 뒤 지운다
    upload_key      varchar(200) NOT NULL,
    -- 메타데이터를 지우고 다시 인코딩한 저장본 키
    object_key      varchar(200),
    content_type    varchar(50),
    size_bytes      bigint,
    width           integer,
    height          integer,
    created_at      timestamp with time zone NOT NULL,
    uploaded_at     timestamp with time zone,
    disposed_at     timestamp with time zone,
    CONSTRAINT community_post_image_status_ck CHECK (status IN ('UPLOADING', 'UNATTACHED', 'ATTACHED', 'DISPOSABLE')),
    CONSTRAINT community_post_image_attached_ck CHECK ((status = 'ATTACHED') = (post_id IS NOT NULL))
);

CREATE INDEX community_post_image_post_idx ON community_post_image (post_id, position) WHERE post_id IS NOT NULL;
CREATE INDEX community_post_image_cleanup_idx ON community_post_image (status, created_at);
CREATE INDEX community_post_image_uploader_idx ON community_post_image (uploader_id);

-- 관리자 삭제 기록 (§3.5, ADR 0012). 지운 본문·이미지는 남기지 않는다. 대상 작성자가 탈퇴해도 지우지 않는다.
CREATE TABLE community_admin_deletion (
    id                  uuid PRIMARY KEY,
    target_type         varchar(10) NOT NULL,
    target_id           uuid NOT NULL,
    target_author_id    uuid NOT NULL,
    neighborhood_code   varchar(10) NOT NULL,
    admin_id            uuid NOT NULL,
    reason              text NOT NULL,
    deleted_at          timestamp with time zone NOT NULL,
    CONSTRAINT community_admin_deletion_type_ck CHECK (target_type IN ('POST', 'COMMENT'))
);

CREATE INDEX community_admin_deletion_target_idx ON community_admin_deletion (target_type, target_id);
