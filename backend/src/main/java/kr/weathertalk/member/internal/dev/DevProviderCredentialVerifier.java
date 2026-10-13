package kr.weathertalk.member.internal.dev;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import kr.weathertalk.member.internal.auth.ProviderCredential;
import kr.weathertalk.member.internal.auth.ProviderCredentialVerifier;
import kr.weathertalk.member.internal.auth.VerifiedProviderAccount;
import kr.weathertalk.member.internal.domain.AuthProvider;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.profile.DevelopmentOnly;

/**
 * 개발 전용 제공자 검증기: 앱이 낸 임의 고유 ID를 그대로 제공자 고유 ID로 인정한다.
 * local·staging·test에서만 등록되며 운영에는 없다(implementation-plan 후순위 원칙, contracts.md §4).
 */
@Component
@DevelopmentOnly
class DevProviderCredentialVerifier implements ProviderCredentialVerifier {

	private static final Pattern DEV_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	@Override
	public AuthProvider provider() {
		return AuthProvider.DEV;
	}

	@Override
	public VerifiedProviderAccount verify(ProviderCredential credential) {
		if (credential.token() == null || !DEV_ID.matcher(credential.token()).matches()) {
			throw new DomainException(ErrorCode.AUTHENTICATION_FAILED, "개발 로그인 ID는 1~100자의 영문·숫자·'.'·'_'·'-'여야 합니다.");
		}
		return new VerifiedProviderAccount(AuthProvider.DEV, credential.token(), credential.claimedEmail());
	}

}
