-- 약관 기준 데이터 (01-member.md §3.5, legacy 01 §8 추가 의문: 약관 마스터는 버전 관리되는 초기 데이터로 적재).
-- 본문은 자리표시다. 법률 검토 후 실제 본문 위치(content_url)와 시행 시각으로 새 버전 마이그레이션을 추가한다.
-- 새 버전 등록 예(중대 개정이면 기존 회원 재동의 필요):
--   INSERT INTO member_terms VALUES ('SERVICE', 2, '서비스 이용약관', true, true, '<시행 시각>', '<본문 URL>');
-- 위치기반서비스 이용약관은 위치정보법 검토 결과에 따라 추가한다(01 §3.5, README 남은 외부 확인 사항).
INSERT INTO member_terms (kind, version, title, required, major_revision, effective_at, content_url) VALUES
    ('SERVICE', 1, '서비스 이용약관(자리표시)', true, true, '2026-01-01 00:00:00+09', 'about:blank#terms-service-v1-placeholder'),
    ('PRIVACY', 1, '개인정보 처리방침(자리표시)', true, true, '2026-01-01 00:00:00+09', 'about:blank#terms-privacy-v1-placeholder');
