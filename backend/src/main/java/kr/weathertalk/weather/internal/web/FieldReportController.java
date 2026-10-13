package kr.weathertalk.weather.internal.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;
import kr.weathertalk.neighborhood.DeviceLocation;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.shared.idempotency.IdempotencyKey;
import kr.weathertalk.shared.idempotency.IdempotentExecutor;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.FieldReportResult;
import kr.weathertalk.weather.FieldReportStatus;
import kr.weathertalk.weather.ForecastDivergence;
import kr.weathertalk.weather.WeatherApi;
import kr.weathertalk.weather.internal.fieldreport.FieldReportService;

/**
 * 현장 제보·예보 vs 주민 차이 웹 API (FR-WEA-04·05·12·14).
 * <ul>
 * <li>제보: 회원 전용 + 약관 재동의 가드 + 요청 식별자 멱등(contracts §4). 좌표는 본문으로만 받고 저장하지 않는다(ADR 0009).</li>
 * <li>취소: 회원 전용. 본인 제보 취소는 약관 재동의 전에도 허용한다(contracts §4).</li>
 * <li>현황·차이: 비회원에게도 공개한다(BR-WEA-22). 회원이면 현황에 본인 제보를 함께 준다.</li>
 * </ul>
 * 종류는 이름(예: {@code RAIN})으로 받고, 모르는 이름은 INVALID_REQUEST.
 */
@RestController
class FieldReportController {

	record SubmitRequest(@NotNull String kind, Double latitude, Double longitude, Double accuracyMeters) {

		@Override
		public String toString() {
			return "SubmitRequest[redacted]";
		}

	}

	private final FieldReportService fieldReports;
	private final WeatherApi weatherApi;
	private final IdempotentExecutor idempotent;

	FieldReportController(FieldReportService fieldReports, WeatherApi weatherApi, IdempotentExecutor idempotent) {
		this.fieldReports = fieldReports;
		this.weatherApi = weatherApi;
		this.idempotent = idempotent;
	}

	/** FR-WEA-04 현장 제보 하기. */
	@PostMapping("/api/v1/weather/field-reports")
	FieldReportResult submit(@RequiresAgreedTerms Requester.Member member, IdempotencyKey key,
			@Valid @RequestBody SubmitRequest request) {
		FieldReportKind kind = parse(request.kind());
		return idempotent.execute(member.memberId(), "weather.field-report", key, FieldReportResult.class,
				() -> fieldReports.submit(member.memberId(), kind,
						new DeviceLocation(request.latitude(), request.longitude(), request.accuracyMeters())));
	}

	/** FR-WEA-14 현장 제보 취소. */
	@PostMapping("/api/v1/weather/field-reports/{reportId}/cancel")
	FieldReportResult cancel(Requester.Member member, @PathVariable UUID reportId) {
		return weatherApi.cancelFieldReport(member.memberId(), reportId);
	}

	/** FR-WEA-05 현장 제보 현황. */
	@GetMapping("/api/v1/weather/neighborhoods/{neighborhoodId}/field-reports")
	FieldReportStatus status(Requester requester, @PathVariable String neighborhoodId) {
		return weatherApi.findFieldReportStatus(new NeighborhoodId(neighborhoodId),
				requester instanceof Requester.Member member ? member.memberId() : null);
	}

	/** FR-WEA-12 예보 vs 주민 차이. */
	@GetMapping("/api/v1/weather/neighborhoods/{neighborhoodId}/divergence")
	ForecastDivergence divergence(@PathVariable String neighborhoodId) {
		return weatherApi.findForecastDivergence(new NeighborhoodId(neighborhoodId));
	}

	private static FieldReportKind parse(String name) {
		try {
			return FieldReportKind.valueOf(name);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "알 수 없는 현장 제보 종류입니다.");
		}
	}

}
