package kr.weathertalk.member.internal.auth;

import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 제공자 측 연결 해제 포트 (FR-MEM-09 규칙 3·4). 카카오는 연결 끊기(unlink), 애플·구글은 보관한 토큰 철회(revoke).
 * 영구 오류 중 '이미 해제됨·토큰 무효'는 성공으로 돌려준다.
 */
public interface ProviderUnlinker {

	AuthProvider provider();

	/** 애플·구글은 로그인 때 보관한 자격이 있어야 해제할 수 있다. 없으면 호출하지 않는다(빈 자격 호출 금지, legacy 01-#4). */
	boolean requiresCredential();

	/** @param credential 복호화한 연결 해제용 자격. 필요 없는 제공자는 null */
	Result unlink(String providerUserId, String credential);

	enum Outcome {

		SUCCEEDED,

		/** 네트워크·5xx·제한·설정 누락 — 백오프 후 재시도 */
		TRANSIENT_FAILURE,

		/** 재시도해도 소용없는 거절 — 바로 실패로 남기고 경고 */
		PERMANENT_FAILURE

	}

	record Result(Outcome outcome, String detail) {

		public static Result succeeded(String detail) {
			return new Result(Outcome.SUCCEEDED, detail);
		}

		public static Result transientFailure(String detail) {
			return new Result(Outcome.TRANSIENT_FAILURE, detail);
		}

		public static Result permanentFailure(String detail) {
			return new Result(Outcome.PERMANENT_FAILURE, detail);
		}

	}

}
