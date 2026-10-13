package kr.weathertalk.weather.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 외부 날씨 원천 연결 설정. 운영 파라미터(§7)가 아니라 연동 설정이라 {@link WeatherProperties}와 나눴다.
 * 인증키가 비어 있어도 기동은 되고, 수집 시 '없음'으로 처리하며 경고 로그를 남긴다.
 *
 * @param jobs 정기 작업(사전 갱신·중기 정기 수집·정리) 등록 여부. 테스트는 끄고 작업 메서드를 직접 부른다.
 */
@Validated
@ConfigurationProperties("weather.source")
public record WeatherSourceProperties(@NotNull @Valid Kma kma, @NotNull @Valid AirKorea airKorea,
		@NotNull @Valid Jobs jobs) {

	public enum KmaProvider {

		/** 공공데이터포털(apis.data.go.kr/1360000). 키 파라미터 serviceKey */
		DATA_GO_KR("serviceKey"),

		/** 기상청 API허브(apihub.kma.go.kr/api/typ02/openApi). 키 파라미터 authKey */
		API_HUB("authKey");

		private final String keyParameter;

		KmaProvider(String keyParameter) {
			this.keyParameter = keyParameter;
		}

		public String keyParameter() {
			return keyParameter;
		}

	}

	/**
	 * 기상청 단기예보(초단기실황·단기예보), 중기예보(육상·기온), 생활기상지수(자외선). 경로는 두 제공처가 같다.
	 *
	 * @param serviceKey 디코딩된(Decoding) 인증키. 비면 수집하지 않는다.
	 */
	public record Kma(@NotNull KmaProvider provider, @NotBlank String baseUrl, String serviceKey) {

		public boolean configured() {
			return serviceKey != null && !serviceKey.isBlank();
		}

	}

	/** 에어코리아 측정소 정보·대기오염 정보(공공데이터포털). @param serviceKey 디코딩된 인증키 */
	public record AirKorea(@NotBlank String baseUrl, String serviceKey) {

		public boolean configured() {
			return serviceKey != null && !serviceKey.isBlank();
		}

	}

	public record Jobs(@NotNull Boolean enabled) {
	}

}
