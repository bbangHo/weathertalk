package kr.weathertalk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import kr.weathertalk.shared.time.TimeConfig;

/** 테스트에서 시각을 옮길 수 있는 Clock (Asia/Seoul). 통합 테스트는 @BeforeEach에서 {@link #reset()}한다. */
public class MutableClock extends Clock {

	public static final Instant BASE = Instant.parse("2026-10-13T00:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(BASE);

	@Override
	public ZoneId getZone() {
		return TimeConfig.SEOUL;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(instant(), zone);
	}

	@Override
	public Instant instant() {
		return now.get();
	}

	public void reset() {
		now.set(BASE);
	}

	public void set(Instant instant) {
		now.set(instant);
	}

	public void advance(Duration duration) {
		now.updateAndGet(i -> i.plus(duration));
	}

}
