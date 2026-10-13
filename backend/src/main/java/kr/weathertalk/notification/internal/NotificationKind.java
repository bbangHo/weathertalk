package kr.weathertalk.notification.internal;

/**
 * 알림 종류 (06 §3.2) + 탭 이동 대상 키(06 §4.1 페이로드 규칙). 현장 제보 강수 알림은 비·눈이 한 종류다(쿨다운 공유).
 */
public enum NotificationKind {

	DAILY_WEATHER("home"),

	PRECIPITATION_FORECAST("home.hourly"),

	FIELD_REPORT_PRECIPITATION("home.field-report"),

	COMMENT("post.detail");

	private final String navigationTarget;

	NotificationKind(String navigationTarget) {
		this.navigationTarget = navigationTarget;
	}

	/** 앱이 알림 탭 때 열 화면 키(home, home.hourly, home.field-report, post.detail). */
	public String navigationTarget() {
		return navigationTarget;
	}

}
