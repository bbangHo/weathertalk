package kr.weathertalk.member.internal.auth.provider;

import java.util.Map;

import org.springframework.stereotype.Component;

import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialVerifier;
import kr.weathertalk.member.internal.auth.ProviderUnlinker;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.config.MemberProviderProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;
import tools.jackson.databind.JsonNode;

/**
 * 카카오 로그인 검증·연결 끊기 (BR-MEM-03, FR-MEM-09 규칙 3).
 * <ul>
 * <li>검증: 앱이 준 접근 토큰으로 토큰 정보(/v1/user/access_token_info)를 조회해 유효성과 <b>앱 ID 일치</b>를 확인하고 회원번호를 쓴다
 * (다른 앱이 발급받은 토큰으로 로그인하는 것을 막는다, legacy 01-#13). 이메일은 사용자 정보(/v2/user/me)에서 있으면 받는다.</li>
 * <li>해제: 어드민 키 + 회원번호로 연결 끊기(/v1/user/unlink)를 호출한다. 로그아웃 API가 아니다(legacy 01-#2).</li>
 * </ul>
 */
@Component
class KakaoProvider implements ProviderCredentialVerifier, ProviderUnlinker {

	static final String TOKEN_INFO = "/v1/user/access_token_info";
	static final String USER_ME = "/v2/user/me";
	static final String UNLINK = "/v1/user/unlink";

	/** 카카오 오류 코드: 앱과 연결되지 않은 사용자(이미 연결 끊김) */
	private static final int NOT_LINKED = -101;

	private final MemberProviderProperties.Kakao kakao;
	private final ProviderHttp http;

	KakaoProvider(MemberProviderProperties properties) {
		this.kakao = properties.kakao();
		this.http = new ProviderHttp(properties.timeout());
	}

	@Override
	public AuthProvider provider() {
		return AuthProvider.KAKAO;
	}

	@Override
	public boolean available() {
		return kakao.loginConfigured();
	}

	@Override
	public VerifiedProviderAccount verify(ProviderCredential credential) {
		if (!kakao.loginConfigured()) {
			throw ProviderHttp.notConfigured();
		}
		String token = credential.token();
		if (token == null || token.isBlank()) {
			throw ProviderHttp.rejected();
		}
		try {
			ProviderHttp.Response info = http.get(kakao.apiBaseUrl() + TOKEN_INFO, h -> h.setBearerAuth(token));
			if (info.serverError()) {
				throw ProviderHttp.unavailable();
			}
			if (!info.ok()) {
				throw ProviderHttp.rejected();
			}
			JsonNode json = info.json();
			String appId = ProviderHttp.text(json, "app_id");
			String userId = ProviderHttp.text(json, "id");
			if (userId == null || !kakao.appId().strip().equals(appId)) {
				throw ProviderHttp.rejected();
			}
			return new VerifiedProviderAccount(AuthProvider.KAKAO, userId, email(token));
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			throw ProviderHttp.unavailable();
		}
	}

	/** 이메일은 선택 정보라 사용자 정보 조회가 실패해도 로그인은 계속한다. */
	private String email(String token) {
		try {
			ProviderHttp.Response me = http.get(kakao.apiBaseUrl() + USER_ME, h -> h.setBearerAuth(token));
			if (!me.ok()) {
				return null;
			}
			JsonNode account = me.json().path("kakao_account");
			if (account.path("is_email_valid").asBoolean(true) && account.path("is_email_verified").asBoolean(true)) {
				return ProviderHttp.text(account, "email");
			}
			return null;
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			return null;
		}
	}

	@Override
	public boolean requiresCredential() {
		return false;
	}

	@Override
	public Result unlink(String providerUserId, String credential) {
		if (kakao.adminKey() == null || kakao.adminKey().isBlank()) {
			return Result.transientFailure("KAKAO_ADMIN_KEY 미설정");
		}
		try {
			ProviderHttp.Response response = http.postForm(kakao.apiBaseUrl() + UNLINK,
					h -> h.set("Authorization", "KakaoAK " + kakao.adminKey().strip()),
					Map.of("target_id_type", "user_id", "target_id", providerUserId));
			if (response.ok()) {
				return Result.succeeded("unlinked");
			}
			if (response.json().path("code").asInt(0) == NOT_LINKED) {
				return Result.succeeded("already-unlinked");
			}
			if (response.serverError() || response.status() == 401) {
				// 401은 어드민 키 오류 — 설정을 고치면 재시도로 풀리게 한다
				return Result.transientFailure("HTTP " + response.status());
			}
			return Result.permanentFailure("HTTP " + response.status() + " " + abbreviate(response.body()));
		}
		catch (ProviderHttp.ProviderUnavailableException e) {
			return Result.transientFailure("network");
		}
	}

	static String abbreviate(String body) {
		if (body == null) {
			return "";
		}
		String oneLine = body.replaceAll("\\s+", " ");
		return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
	}

}
