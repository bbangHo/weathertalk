package kr.weathertalk.member.internal.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.internal.config.MemberProviderProperties;

/**
 * 제공자 연결 해제용 자격(애플·구글 갱신 토큰) 암호화 보관 (01 §3.2, 미결 #4 — 받아들이는 쪽으로 구현).
 * <p>
 * AES-256-GCM, 키 = SHA-256(MEMBER_PROVIDER_TOKEN_KEY). 저장 형식은 {@code v1:base64(IV ‖ 암호문)}.
 * 키가 없으면 자격을 보관하지 않는다(로그인은 정상, 탈퇴 때 그 계정의 제공자 해제만 건너뛴다).
 */
@Component
public class ProviderCredentialCipher {

	private static final Logger log = LoggerFactory.getLogger(ProviderCredentialCipher.class);
	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec key;

	ProviderCredentialCipher(MemberProviderProperties properties) {
		String secret = properties.credentialKey();
		if (secret == null || secret.isBlank()) {
			key = null;
			log.warn("MEMBER_PROVIDER_TOKEN_KEY가 비어 있어 애플·구글 연결 해제용 자격을 보관하지 않습니다(탈퇴 시 제공자 해제 생략).");
		}
		else {
			key = new SecretKeySpec(sha256(secret), "AES");
		}
	}

	public boolean enabled() {
		return key != null;
	}

	/** @return 암호문. 원문이 없거나 키가 없으면 null */
	public String encrypt(String plain) {
		if (plain == null || plain.isBlank() || key == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder()
				.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("자격 암호화에 실패했습니다.", e);
		}
	}

	/** @return 원문. 암호문이 없거나 키가 없거나 키가 바뀌어 풀 수 없으면 null(빈 자격으로 제공자를 호출하지 않는다, legacy 01-#4) */
	public String decrypt(String stored) {
		if (stored == null || !stored.startsWith(PREFIX) || key == null) {
			return null;
		}
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
			return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			log.warn("보관한 제공자 자격을 풀 수 없습니다(키 변경 가능성): {}", e.getClass().getSimpleName());
			return null;
		}
	}

	private static byte[] sha256(String secret) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

}
