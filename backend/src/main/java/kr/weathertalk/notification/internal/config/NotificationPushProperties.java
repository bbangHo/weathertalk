package kr.weathertalk.notification.internal.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 푸시 제공자(FCM HTTP v1) 연결 설정(운영 파라미터 아님). 서비스 계정 키 파일 경로는 환경 변수 FIREBASE_CREDENTIALS_PATH로 받는다.
 * 비어 있거나 읽을 수 없으면 기동은 되고 발송만 비활성(발송 기록 '생략(발송 비활성)', 경고 로그)이다.
 */
@Validated
@ConfigurationProperties("notification.push")
public record NotificationPushProperties(@NotNull @Valid Fcm fcm) {

	/**
	 * @param baseUrl FCM HTTP v1 주소(https://fcm.googleapis.com)
	 * @param credentialsPath Firebase 서비스 계정 키(JSON) 경로. 저장소 밖에 둔다
	 * @param timeout 건별 호출 제한시간
	 */
	public record Fcm(@NotBlank String baseUrl, String credentialsPath, @NotNull @DurationMin(millis = 100) Duration timeout) {
	}

}
