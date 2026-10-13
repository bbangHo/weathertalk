package kr.weathertalk.feeling.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.weathertalk.feeling.FeelingRecorded;
import kr.weathertalk.feeling.internal.config.FeelingProperties;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.shared.Ids.FeelingRecordId;
import kr.weathertalk.shared.Ids.MemberId;
import kr.weathertalk.shared.Ids.NeighborhoodId;

/**
 * 체감 기록 저장 트랜잭션 (BR-FEL-04, 불변식 3). 같은 회원·동네를 잠근 뒤 생성 시각이 feeling.record-interval 안인 기록이 있으면
 * 덮어쓰고(간격은 생성 시각 기준이라 반복 덮어쓰기로 늘지 않는다), 없으면 새로 만든다. 체감기록됨을 같은 트랜잭션에서 발행한다.
 * 외부 조회(동네 확정·관측)는 이 트랜잭션 밖에서 끝낸다(외부 지연 동안 잠금을 쥐지 않게).
 */
@Component
class FeelingRecordWriter {

	private final FeelingRecordRepository records;
	private final ApplicationEventPublisher events;
	private final FeelingProperties properties;

	FeelingRecordWriter(FeelingRecordRepository records, ApplicationEventPublisher events,
			FeelingProperties properties) {
		this.records = records;
		this.events = events;
		this.properties = properties;
	}

	@Transactional
	FeelingRecordResult write(MemberId memberId, NeighborhoodId neighborhoodId, Instant now,
			StoredFeelingRecord.Content content, TemperatureSensitivity sensitivity, ObservationSnapshot snapshot) {
		UUID member = memberId.value();
		String code = neighborhoodId.value();
		records.lockMemberNeighborhood(member, code);
		Optional<StoredFeelingRecord> existing = records.findCreatedAfter(member, code,
				now.minus(properties.recordInterval()));
		StoredFeelingRecord saved;
		FeelingRecorded.Change change;
		if (existing.isPresent()) {
			saved = existing.get().overwrite(now, content, sensitivity, snapshot);
			records.update(saved);
			change = FeelingRecorded.Change.OVERWRITTEN;
		}
		else {
			saved = StoredFeelingRecord.create(member, code, now, content, sensitivity, snapshot);
			records.insert(saved);
			change = FeelingRecorded.Change.CREATED;
		}
		events.publishEvent(new FeelingRecorded(UUID.randomUUID(), memberId, new FeelingRecordId(saved.id()),
				neighborhoodId, saved.recordedAt(), saved.source(), change, now));
		return new FeelingRecordResult(saved.id(), code, saved.createdAt(), saved.recordedAt(), saved.temperature(),
				saved.humidity(), saved.wind(), saved.sensitivity(), saved.source(), saved.snapshot().secured(),
				change == FeelingRecorded.Change.OVERWRITTEN, saved.createdAt().plus(properties.recordInterval()));
	}

}
