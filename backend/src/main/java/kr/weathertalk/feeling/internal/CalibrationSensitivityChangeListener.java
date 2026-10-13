package kr.weathertalk.feeling.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kr.weathertalk.member.TemperatureSensitivityChanged;

/**
 * 온도민감도변경됨 구독 (04 §6.4): 과거 체감 기록은 바꾸지 않는다(기록 시점 민감도 불변). 보정 제안 상태·쿨다운 기준 시각만 갱신한다.
 */
@Component
class CalibrationSensitivityChangeListener {

	private final FeelingCalibrationService calibration;

	CalibrationSensitivityChangeListener(FeelingCalibrationService calibration) {
		this.calibration = calibration;
	}

	@ApplicationModuleListener
	void on(TemperatureSensitivityChanged event) {
		calibration.onSensitivityChanged(event);
	}

}
