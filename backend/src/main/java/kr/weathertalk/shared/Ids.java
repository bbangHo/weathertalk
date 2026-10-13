package kr.weathertalk.shared;

import java.util.UUID;

/**
 * 모듈 간에 오가는 식별자 타입. 다른 모듈의 엔티티를 직접 참조하지 않고 이 ID로만 참조한다 (ADR 0010).
 */
public final class Ids {

	private Ids() {
	}

	public record MemberId(UUID value) {
		public static MemberId random() {
			return new MemberId(UUID.randomUUID());
		}
	}

	/** 동네(행정동). 값은 행정동 코드다. */
	public record NeighborhoodId(String value) {
	}

	public record FeelingRecordId(UUID value) {
	}

	public record PostId(UUID value) {
	}

	public record CommentId(UUID value) {
	}

	public record DeviceId(UUID value) {
	}

}
