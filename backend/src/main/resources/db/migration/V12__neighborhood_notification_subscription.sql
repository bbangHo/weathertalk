-- neighborhood 모듈 M7: 알림 구독 동네 반영 (docs/to-be/02-neighborhood.md FR-NBR-10, BR-NBR-09)
-- 동네별 알림 구독 수(활성 사유). notification이 증감 명령으로 갱신하고, 어긋남은 notification의 대조 배치가 전체 값으로 바로잡는다.
-- 구독의 종류·기기는 알지 못한다.
CREATE TABLE nbr_notification_subscription (
    neighborhood_code  varchar(10) PRIMARY KEY REFERENCES nbr_neighborhood (code),
    subscription_count integer NOT NULL,
    updated_at         timestamp with time zone NOT NULL,
    CONSTRAINT nbr_notification_subscription_count_ck CHECK (subscription_count >= 0)
);
