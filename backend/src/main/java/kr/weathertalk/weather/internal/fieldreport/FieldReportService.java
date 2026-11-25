package kr.weathertalk.weather.internal.fieldreport;

import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.weathertalk.neighborhood.Neighborhood;
import kr.weathertalk.neighborhood.NeighborhoodApi;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;
import kr.weathertalk.shared.error.DomainException;
import kr.weathertalk.shared.error.ErrorCode;
import kr.weathertalk.weather.FieldReport;
import kr.weathertalk.weather.FieldReportKind;
import kr.weathertalk.weather.FieldReportResult;
import kr.weathertalk.weather.FieldReportStatus;
import kr.weathertalk.weather.internal.config.WeatherProperties;
import kr.weathertalk.weather.internal.fieldreport.FieldReportRepository.StoredFieldReport;

/**
 * 현장 제보 하기·취소·현황 (FR-WEA-04·05·14, BR-WEA-15). 회원·약관 확인과 요청 식별자 멱등은 컨트롤러에서 끝난다.
 * 쓰기 직전 neighborhood 현재 동네 확정으로 동네를 정한다 — 앱이 기기 안에서 판정한 동네 ID를 받는다(좌표는 받지 않음, ADR 0009).
 */
@Service
public class FieldReportService {

	private final NeighborhoodApi neighborhoods;
	private final FieldReportWriter writer;
	private final FieldReportAggregator aggregator;
	private final WeatherProperties properties;

	FieldReportService(NeighborhoodApi neighborhoods, FieldReportWriter writer, FieldReportAggregator aggregator,
			WeatherProperties properties) {
		this.neighborhoods = neighborhoods;
		this.writer = writer;
		this.aggregator = aggregator;
		this.properties = properties;
	}

	public FieldReportResult submit(MemberId memberId, FieldReportKind kind, NeighborhoodId neighborhoodId) {
		if (kind == null) {
			throw new DomainException(ErrorCode.INVALID_REQUEST, "현장 제보 종류를 골라 주세요.");
		}
		Neighborhood neighborhood = neighborhoods.confirmCurrentNeighborhood(memberId, neighborhoodId).orElseThrow();
		StoredFieldReport saved = writer.write(memberId, neighborhood.id(), kind);
		return result(saved, memberId);
	}

	public FieldReportResult cancel(MemberId memberId, UUID reportId) {
		if (reportId == null) {
			throw new DomainException(ErrorCode.NOT_FOUND, "현장 제보를 찾을 수 없습니다.");
		}
		return result(writer.cancel(memberId, reportId), memberId);
	}

	public FieldReportStatus status(NeighborhoodId neighborhoodId, MemberId requesterId) {
		return aggregator.status(neighborhoodId, requesterId);
	}

	private FieldReportResult result(StoredFieldReport report, MemberId memberId) {
		FieldReport view = FieldReportAggregator.toView(report, properties);
		return new FieldReportResult(view, aggregator.status(view.neighborhoodId(), memberId));
	}

}
