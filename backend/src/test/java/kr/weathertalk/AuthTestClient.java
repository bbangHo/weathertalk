package kr.weathertalk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import kr.weathertalk.shared.Ids.MemberId;

/** 개발 전용 로그인 → 가입 완료를 실제 API로 거쳐 회원과 토큰을 만든다. */
public class AuthTestClient {

	public record Session(MemberId memberId, String accessToken, String refreshToken, String providerUserId) {

		public String bearer() {
			return "Bearer " + accessToken;
		}

	}

	private final MockMvc mvc;

	public AuthTestClient(MockMvc mvc) {
		this.mvc = mvc;
	}

	public static String newProviderUserId() {
		return "dev-" + UUID.randomUUID();
	}

	public ResultActions devLogin(String providerUserId, String email) throws Exception {
		String body = email == null ? "{\"providerUserId\":\"" + providerUserId + "\"}"
				: "{\"providerUserId\":\"" + providerUserId + "\",\"email\":\"" + email + "\"}";
		return mvc.perform(post("/api/v1/dev/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	public String signupTicket(String providerUserId) throws Exception {
		String json = devLogin(providerUserId, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.signupTicket.ticket");
	}

	/** 현재 시행 중인 약관 전부에 동의하는 목록. */
	public List<Map<String, Object>> agreeAll() throws Exception {
		String json = mvc.perform(get("/api/v1/terms")).andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> terms = JsonPath.read(json, "$");
		return terms.stream()
			.map(t -> Map.<String, Object>of("kind", t.get("kind"), "version", t.get("version"), "agreed", true))
			.toList();
	}

	public ResultActions completeSignup(String ticket, List<Map<String, Object>> agreements, String sensitivity)
			throws Exception {
		StringBuilder items = new StringBuilder();
		for (Map<String, Object> a : agreements) {
			if (!items.isEmpty()) {
				items.append(',');
			}
			items.append("{\"kind\":\"").append(a.get("kind")).append("\",\"version\":").append(a.get("version"))
				.append(",\"agreed\":").append(a.get("agreed")).append('}');
		}
		String sensitivityJson = sensitivity == null ? "null" : "\"" + sensitivity + "\"";
		return mvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"signupTicket\":\"" + ticket + "\",\"agreements\":[" + items + "],\"temperatureSensitivity\":"
					+ sensitivityJson + "}"));
	}

	public Session signup() throws Exception {
		return signup(newProviderUserId());
	}

	public Session signup(String providerUserId) throws Exception {
		String ticket = signupTicket(providerUserId);
		String json = completeSignup(ticket, agreeAll(), "NORMAL").andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return session(json, providerUserId);
	}

	public static Session session(String loginResponseJson, String providerUserId) {
		return new Session(new MemberId(UUID.fromString(JsonPath.read(loginResponseJson, "$.member.memberId"))),
				JsonPath.read(loginResponseJson, "$.tokens.accessToken"),
				JsonPath.read(loginResponseJson, "$.tokens.refreshToken"), providerUserId);
	}

}
