package kr.weathertalk.notification;

/** OS 알림 권한 (06 §3.1, 앱 보고값). '거부'인 기기에는 보내지 않는다(BR-NOTI-16). */
public enum OsNotificationPermission {

	GRANTED, DENIED, UNKNOWN

}
