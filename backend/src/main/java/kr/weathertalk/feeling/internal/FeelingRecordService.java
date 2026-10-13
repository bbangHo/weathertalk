package kr.weathertalk.feeling.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.stereotype.Service;

import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.FeedbackAnswer;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.MemberApi;
import kr.weathertalk.member.MemberProfile;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.neighborhood.DeviceLocation;
import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;

/**
 * 체감 기록 남기기(FR-FEL-01)와 체감 피드백 남기기(FR-FEL-02). 회원·약관 확인은 컨트롤러 파라미터에서 끝난다.
 * <ol>
 * <li>기기 좌표로 neighborhood 현재 동네 확정 — 거부되면 기록하지 않는다. 앱이 보낸 동네 ID는 믿지 않고 좌표도 저장하지 않는다(BR-FEL-02).</li>
 * <li>회원의 현재 온도 민감도를 복사한다.</li>
 * <li>관측 스냅샷(실패해도 '미확보'로 저장, BR-FEL-05).</li>
 * <li>덮어쓰기 또는 새 기록(BR-FEL-04) — {@link FeelingRecordWriter}.</li>
 * </ol>
 */
@Service
public class FeelingRecordService {

	/** @param humidity·wind 선택. 직접 기록 화면은 아무 태그도 미리 고르지 않는다(BR-FEL-03, 앱 규칙). */
	public record RecordCommand(TemperatureTag temperature, HumidityTag humidity, WindTag wind,
			DeviceLocation location) {
	}

	/**
	 * 보여준 민감도별 체감과 그에 대한 응답 (FR-FEL-02 입력).
	 *
	 * @param shownBasisTime 보여준 값의 기준 시각(정시)
	 * @param shownDerivedAt 보여준 값의 산출 시각
	 */
	public record FeedbackCommand(FeedbackAnswer answer, TemperatureTag shownTemperature, DerivationSource shownSource,
			Integer shownMappingVersion, TemperatureSensitivity shownSensitivity, NeighborhoodId shownNeighborhoodId,
			Instant shownBasisTime, Instant shownDerivedAt, DeviceLocation location) {
	}

	private final NeighborhoodApi neighborhoodApi;
	private final MemberApi memberApi;
	private final SnapshotTaker snapshots;
	private final FeelingRecordWriter writer;
	private final FeelingProperties properties;
	private final Clock clock;

	FeelingRecordService(NeighborhoodApi neighborhoodApi, MemberApi memberApi, SnapshotTaker snapshots,
			FeelingRecordWriter writer, FeelingProperties properties, Clock clock) {
		this.neighborhoodApi = neighborhoodApi;
		this.memberApi = memberApi;
		this.snapshots = snapshots;
		this.writer = writer;
		this.properties = properties;
		this.clock = clock;
	}

	public FeelingRecordResult record(MemberId memberId, RecordCommand command) {
		if (command.temperature() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "온도 체감 태그는 필수입니다.");
		}
		Neighborhood neighborhood = neighborhoodApi.confirmCurrentNeighborhood(memberId, command.location())
			.orElseThrow();
		TemperatureSensitivity sensitivity = currentSensitivity(memberId);
		Instant now = clock.instant();
		ObservationSnapshot snapshot = snapshots.take(neighborhood.id(), now);
		return writer.write(memberId, neighborhood.id(), now, new StoredFeelingRecord.Content(command.temperature(),
				command.humidity(), command.wind(), null), sensitivity, snapshot);
	}

	public FeelingRecordResult feedback(MemberId memberId, FeedbackCommand command) {
		if (command.answer() == null || command.shownTemperature() == null || command.shownSource() == null
				|| command.shownSensitivity() == null || command.shownNeighborhoodId() == null
				|| command.shownBasisTime() == null || command.shownDerivedAt() == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "피드백 응답과 보여준 값 정보가 모두 필요합니다.");
		}
		Neighborhood neighborhood = neighborhoodApi.confirmCurrentNeighborhood(memberId, command.location())
			.orElseThrow();
		TemperatureSensitivity sensitivity = currentSensitivity(memberId);
		Instant now = clock.instant();
		validateTarget(command, neighborhood, sensitivity, now);
		TemperatureTag converted = command.answer().apply(command.shownTemperature());
		ObservationSnapshot snapshot = snapshots.take(neighborhood.id(), now);
		StoredFeelingRecord.Feedback feedback = new StoredFeelingRecord.Feedback(command.answer(),
				command.shownTemperature(), command.shownSource(), command.shownMappingVersion());
		return writer.write(memberId, neighborhood.id(), now,
				new StoredFeelingRecord.Content(converted, null, null, feedback), sensitivity, snapshot);
	}

	/**
	 * 피드백 대상 (BR-FEL-07, FR-FEL-02 규칙 1·2): 현재 동네 · 회원의 현재 민감도 · 산출 당시의 현재 시각으로 산출해 보여준 값이고,
	 * 산출 후 feeling.feedback.max-shown-age 안이어야 한다. 미래(외출 시각)·과거(어제) 값은 기준 시각이 산출 시각의 정시와 달라 걸러진다.
	 */
	private void validateTarget(FeedbackCommand command, Neighborhood neighborhood, TemperatureSensitivity sensitivity,
			Instant now) {
		if (!command.shownNeighborhoodId().equals(neighborhood.id())) {
			throw invalidTarget("NEIGHBORHOOD_MISMATCH", "현재 동네의 체감에만 답할 수 있습니다.");
		}
		if (command.shownSensitivity() != sensitivity) {
			throw invalidTarget("SENSITIVITY_MISMATCH", "내 온도 민감도 그룹의 체감에만 답할 수 있습니다.");
		}
		if (!command.shownBasisTime().equals(command.shownDerivedAt().truncatedTo(ChronoUnit.HOURS))) {
			throw invalidTarget("NOT_CURRENT_TIME", "지금 시각의 체감에만 답할 수 있습니다.");
		}
		Duration age = Duration.between(command.shownDerivedAt(), now);
		if (age.isNegative() || age.compareTo(properties.feedback().maxShownAge()) > 0) {
			throw invalidTarget("STALE", "보여준 체감이 오래됐습니다. 새로 불러온 뒤 답해 주세요.");
		}
	}

	private TemperatureSensitivity currentSensitivity(MemberId memberId) {
		return memberApi.findProfile(memberId)
			.filter(profile -> !profile.withdrawn())
			.map(MemberProfile::temperatureSensitivity)
			.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
	}

	private static DomainException invalidTarget(String reason, String message) {
		return new DomainException(ErrorCode.FEEDBACK_TARGET_INVALID, message, Map.of("reason", reason));
	}

}
