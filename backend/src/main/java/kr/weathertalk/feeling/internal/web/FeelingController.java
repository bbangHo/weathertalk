package kr.weathertalk.feeling.internal.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.FeedbackAnswer;
import kr.weathertalk.feeling.FeelingApi;
import kr.weathertalk.feeling.FeelingTag;
import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.MyFeelingRecords;
import kr.weathertalk.feeling.NeighborhoodFeeling;
import kr.weathertalk.feeling.SensitivityFeelings;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.feeling.YesterdayComparison;
import kr.weathertalk.feeling.internal.FeelingRecordResult;
import kr.weathertalk.feeling.internal.FeelingRecordService;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.idempotency.IdempotencyKey;
import kr.weathertalk.shared.idempotency.IdempotentExecutor;

/**
 * 체감 웹 API (04 §4). 기록·피드백은 회원 전용 + 약관 재동의 가드 + 요청 식별자 멱등(contracts §4). 좌표는 받지 않고
 * 앱이 기기 안에서 판정한 동네 ID를 받는다(ADR 0009). 조회(동네 체감·민감도별 체감·어제와 비교)는 비회원도 할 수 있다(BR-FEL-01).
 * 태그는 이름(예: {@code SLIGHTLY_COLD})으로 주고받으며, 범주에 없는 값·온도 누락은 INVALID_REQUEST(legacy 03-#6).
 */
@RestController
class FeelingController {

	/**
	 * 체감 기록 남기기 요청. 습도·바람은 선택. 태그는 문자열로 받아 이름으로만 해석한다 — JSON 숫자가 열거형 순서(ordinal)로
	 * 바뀌어 엉뚱한 단계가 저장되지 않게(단계 번호 ≠ 순서 번호).
	 */
	record RecordRequest(@NotNull String temperature, String humidity, String wind, String neighborhoodId) {

		@Override
		public String toString() {
			return "RecordRequest[redacted]";
		}

	}

	/** 앱이 보여준 민감도별 체감(산출 응답의 값을 그대로 돌려보낸다). */
	record ShownFeeling(@NotNull String temperatureTag, @NotNull String source, Integer mappingVersion,
			@NotNull String temperatureSensitivity, @NotBlank String neighborhoodId, @NotNull Instant basisTime,
			@NotNull Instant derivedAt) {
	}

	record FeedbackRequest(@NotNull String answer, @NotNull @Valid ShownFeeling shown,
			String neighborhoodId) {

		@Override
		public String toString() {
			return "FeedbackRequest[redacted]";
		}

	}

	record TagDefinition(String name, int level, String label) {

		static List<TagDefinition> of(FeelingTag[] tags) {
			return Arrays.stream(tags).map(t -> new TagDefinition(((Enum<?>) t).name(), t.level(), t.label())).toList();
		}

	}

	/** 체감 태그 정의 — 앱의 선택지·표시문과 서버 검증이 같은 정의에서 나온다(legacy 03-#4). */
	record TagDefinitions(List<TagDefinition> temperature, List<TagDefinition> humidity, List<TagDefinition> wind) {
	}

	private final FeelingRecordService recordService;
	private final FeelingApi feelingApi;
	private final IdempotentExecutor idempotent;

	FeelingController(FeelingRecordService recordService, FeelingApi feelingApi, IdempotentExecutor idempotent) {
		this.recordService = recordService;
		this.feelingApi = feelingApi;
		this.idempotent = idempotent;
	}

	@GetMapping("/api/v1/feeling/tags")
	TagDefinitions tags() {
		return new TagDefinitions(TagDefinition.of(TemperatureTag.values()), TagDefinition.of(HumidityTag.values()),
				TagDefinition.of(WindTag.values()));
	}

	/** FR-FEL-01 체감 기록 남기기(직접). */
	@PostMapping("/api/v1/feeling/records")
	FeelingRecordResult record(@RequiresAgreedTerms Requester.Member member, IdempotencyKey key,
			@Valid @RequestBody RecordRequest request) {
		return idempotent.execute(member.memberId(), "feeling.record", key, FeelingRecordResult.class,
				() -> recordService.record(member.memberId(), new FeelingRecordService.RecordCommand(
						parse(TemperatureTag.class, request.temperature()), parse(HumidityTag.class, request.humidity()),
						parse(WindTag.class, request.wind()),
						new NeighborhoodId(request.neighborhoodId()))));
	}

	/** FR-FEL-02 체감 피드백 남기기. */
	@PostMapping("/api/v1/feeling/feedbacks")
	FeelingRecordResult feedback(@RequiresAgreedTerms Requester.Member member, IdempotencyKey key,
			@Valid @RequestBody FeedbackRequest request) {
		ShownFeeling shown = request.shown();
		return idempotent.execute(member.memberId(), "feeling.feedback", key, FeelingRecordResult.class,
				() -> recordService.feedback(member.memberId(),
						new FeelingRecordService.FeedbackCommand(parse(FeedbackAnswer.class, request.answer()),
								parse(TemperatureTag.class, shown.temperatureTag()),
								parse(DerivationSource.class, shown.source()), shown.mappingVersion(),
								parse(TemperatureSensitivity.class, shown.temperatureSensitivity()),
								new NeighborhoodId(shown.neighborhoodId()), shown.basisTime(), shown.derivedAt(),
								new NeighborhoodId(request.neighborhoodId()))));
	}

	/** FR-FEL-03 동네 체감. */
	@GetMapping("/api/v1/feeling/neighborhoods/{neighborhoodId}")
	NeighborhoodFeeling neighborhoodFeeling(@PathVariable String neighborhoodId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
		return feelingApi.findNeighborhoodFeeling(new NeighborhoodId(neighborhoodId), instant(at));
	}

	/** FR-FEL-04 민감도별 체감. 민감도를 주면 그 그룹만, 없으면 3그룹. */
	@GetMapping("/api/v1/feeling/neighborhoods/{neighborhoodId}/sensitivity-feelings")
	SensitivityFeelings sensitivityFeelings(@PathVariable String neighborhoodId,
			@RequestParam(required = false) TemperatureSensitivity temperatureSensitivity,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
		NeighborhoodId id = new NeighborhoodId(neighborhoodId);
		if (temperatureSensitivity == null) {
			return feelingApi.findSensitivityFeelings(id, instant(at));
		}
		var groups = feelingApi.findSensitivityFeeling(id, temperatureSensitivity, instant(at)).stream().toList();
		return new SensitivityFeelings(id, groups.isEmpty() ? null : groups.getFirst().basisTime(), groups);
	}

	/** FR-FEL-05 어제와 비교. */
	@GetMapping("/api/v1/feeling/neighborhoods/{neighborhoodId}/yesterday-comparison")
	YesterdayComparison yesterdayComparison(@PathVariable String neighborhoodId,
			@RequestParam TemperatureSensitivity temperatureSensitivity,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
		return feelingApi.compareWithYesterday(new NeighborhoodId(neighborhoodId), temperatureSensitivity,
				instant(at));
	}

	/** FR-FEL-11 마이페이지 내 체감 기록·내 패턴. 조회라 약관 재동의 전에도 허용. */
	@GetMapping("/api/v1/members/me/feeling-records")
	MyFeelingRecords myRecords(Requester.Member member) {
		return feelingApi.findMyRecords(member.memberId());
	}

	/** 이름으로만 해석한다. null은 null(선택 항목), 모르는 이름은 INVALID_REQUEST(legacy 03-#6). */
	private static <E extends Enum<E>> E parse(Class<E> type, String name) {
		if (name == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, name);
		}
		catch (IllegalArgumentException e) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "알 수 없는 값입니다: " + type.getSimpleName());
		}
	}

	private static Instant instant(OffsetDateTime at) {
		return at == null ? null : at.toInstant();
	}

}
