package kr.weathertalk.notification.internal;

import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;

import kr.weathertalk.member.Requester;
import kr.weathertalk.notification.DepartureTimes;
import kr.weathertalk.notification.DeviceConfirmCommand;
import kr.weathertalk.notification.DeviceConfirmation;
import kr.weathertalk.notification.DeviceCredential;
import kr.weathertalk.notification.NotificationApi;

/** {@link NotificationApi} 구현 (contracts.md §2.7). */
@Service
class NotificationApiService implements NotificationApi {

	private final DeviceService devices;

	NotificationApiService(DeviceService devices) {
		this.devices = devices;
	}

	@Override
	public DeviceConfirmation confirmDevice(DeviceConfirmCommand command, Requester requester) {
		return devices.confirm(command, requester);
	}

	@Override
	public DepartureTimes findDepartureTimes(DeviceCredential device) {
		return devices.departureTimes(device);
	}

	@Override
	public DepartureTimes setDepartureTimes(DeviceCredential device, List<LocalTime> times) {
		return devices.setDepartureTimes(device, times);
	}

}
