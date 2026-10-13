package kr.weathertalk.notification.internal.push;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Firebase 서비스 계정 키(JSON)에서 FCM 호출에 필요한 값만 꺼낸다: 프로젝트 ID, 서비스 계정 이메일, 개인 키, 토큰 발급 주소.
 */
record FcmServiceAccount(String projectId, String clientEmail, String privateKeyId, RSAPrivateKey privateKey,
		String tokenUri) {

	private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

	static FcmServiceAccount load(String path) {
		try {
			return parse(Files.readString(Path.of(path), StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new IllegalStateException("파일을 읽을 수 없습니다: " + path);
		}
	}

	static FcmServiceAccount parse(String json) {
		try {
			JsonNode node = JsonMapper.builder().build().readTree(json);
			String projectId = text(node, "project_id");
			String clientEmail = text(node, "client_email");
			String privateKey = text(node, "private_key");
			if (projectId == null || clientEmail == null || privateKey == null) {
				throw new IllegalStateException("project_id·client_email·private_key가 필요합니다");
			}
			String tokenUri = text(node, "token_uri");
			return new FcmServiceAccount(projectId, clientEmail, text(node, "private_key_id"), rsa(privateKey),
					tokenUri == null ? DEFAULT_TOKEN_URI : tokenUri);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("서비스 계정 키 JSON 형식 오류");
		}
	}

	private static RSAPrivateKey rsa(String pem) {
		try {
			String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
			return (RSAPrivateKey) KeyFactory.getInstance("RSA")
				.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
		}
		catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
			throw new IllegalStateException("서비스 계정 개인 키를 읽을 수 없습니다: " + e.getClass().getSimpleName() + " " + e.getMessage());
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asString();
		return text == null || text.isBlank() ? null : text;
	}

	@Override
	public String toString() {
		return "FcmServiceAccount[projectId=" + projectId + ", clientEmail=" + clientEmail + "]";
	}

}
