package kr.weathertalk.member.internal.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.member.internal.MemberAccountService;
import kr.weathertalk.member.internal.config.MemberProviderProperties;
import kr.weathertalk.member.internal.domain.AuthProvider;

/**
 * 카카오 연결 해제 웹훅: 사용자가 카카오 쪽(연결된 서비스 관리·카카오계정 탈퇴 등)에서 앱 연결을 끊으면 카카오가 GET 또는 POST(form)로
 * {@code app_id}·{@code user_id}·{@code referrer_type}을 보낸다. 인증 토큰 없이 열려 있으므로 {@code Authorization: KakaoAK <어드민 키>}와
 * 앱 ID로 진위를 확인한다. 처리는 {@link MemberAccountService#handleProviderUnlinked}(유일한 로그인 수단이면 탈퇴). 카카오는 3초 안에 200을 요구하고, 모르는 사용자·중복 알림에도 200을 돌려준다.
 * (https://developers.kakao.com/docs/latest/ko/kakaologin/callback)
 */
@RestController
class KakaoUnlinkWebhookController {

	private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkWebhookController.class);

	private final MemberAccountService accountService;
	private final MemberProviderProperties.Kakao kakao;

	KakaoUnlinkWebhookController(MemberAccountService accountService, MemberProviderProperties properties) {
		this.accountService = accountService;
		this.kakao = properties.kakao();
	}

	@RequestMapping(path = "/api/v1/webhooks/kakao/unlink", method = { RequestMethod.GET, RequestMethod.POST })
	ResponseEntity<Void> unlinked(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(name = "app_id", required = false) String appId,
			@RequestParam(name = "user_id", required = false) String userId,
			@RequestParam(name = "referrer_type", required = false) String referrerType) {
		if (!adminKeyMatches(authorization)) {
			log.warn("카카오 연결 해제 웹훅: 어드민 키 불일치로 거부");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		if (appId == null || !appId.strip().equals(kakao.appId().strip())) {
			log.warn("카카오 연결 해제 웹훅: 앱 ID 불일치로 거부");
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		if (userId != null && !userId.isBlank()) {
			MemberAccountService.ProviderUnlinkOutcome outcome = accountService
				.handleProviderUnlinked(AuthProvider.KAKAO, userId.strip());
			log.info("카카오 연결 해제 웹훅: referrerType={}, 결과={}", referrerType, outcome);
		}
		return ResponseEntity.ok().build();
	}

	/** 상수 시간 비교. 어드민 키가 설정되지 않았으면 진위를 확인할 수 없으니 거부한다. */
	private boolean adminKeyMatches(String authorization) {
		String adminKey = kakao.adminKey();
		if (adminKey == null || adminKey.isBlank() || !kakao.loginConfigured() || authorization == null) {
			return false;
		}
		return MessageDigest.isEqual(("KakaoAK " + adminKey.strip()).getBytes(StandardCharsets.UTF_8),
				authorization.strip().getBytes(StandardCharsets.UTF_8));
	}

}
