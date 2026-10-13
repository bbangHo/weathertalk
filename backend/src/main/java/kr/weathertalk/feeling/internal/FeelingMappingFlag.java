package kr.weathertalk.feeling.internal;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.internal.config.FeelingProperties;

/**
 * 체감 매핑 기능 플래그 (feeling.mapping.enabled, implementation-plan M6). 켜져 있을 때만 적용 중인 매핑 버전을
 * 민감도별 체감 2단계·내 체감 패턴·보정 제안 판정에 쓴다. 초기값은 설정이며, 테스트가 실행 중에 바꿀 수 있도록 값을 들고 있다.
 */
@Component
public class FeelingMappingFlag {

	private final AtomicBoolean enabled;

	FeelingMappingFlag(FeelingProperties properties) {
		this.enabled = new AtomicBoolean(properties.mapping().enabled());
	}

	public boolean enabled() {
		return enabled.get();
	}

	/** @return 바꾸기 전 값 */
	public boolean set(boolean value) {
		return enabled.getAndSet(value);
	}

}
