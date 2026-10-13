package kr.weathertalk.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.weathertalk.feeling.HumidityTag;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.feeling.WindTag;
import kr.weathertalk.member.TemperatureSensitivity;

/**
 * 피드·상세·인기글·내가 쓴 글의 게시글 항목 (FR-COM-05 규칙 5).
 *
 * @param neighborhood 게시글의 동네(작성 시 고정, 작성자의 현재 동네가 아니다 — BR-COM-01)
 * @param attachedFeelingRecord 첨부된 체감 기록. 없거나 조회할 수 없으면 null(FR-COM-05 규칙 6)
 * @param edited 수정된 적이 있으면 "수정됨" 표시
 * @param commentCount 댓글+답글 중 삭제된 댓글 자리를 뺀 수
 * @param author 현재 닉네임·현재 온도 민감도(BR-COM-18). 프로필 사진은 없다
 * @param likedByViewer 조회자의 좋아요 여부. 비회원이면 null
 * @param mine 조회자 본인 글 여부. 비회원이면 null
 */
public record PostSummary(UUID postId, String body, List<Image> images, Neighborhood neighborhood,
		AttachedFeelingRecord attachedFeelingRecord, Instant createdAt, boolean edited, Instant updatedAt,
		int likeCount, int commentCount, Author author, Boolean likedByViewer, Boolean mine) {

	/**
	 * @param url 표시용 사전 서명 주소(community.image.view-url-ttl 동안 유효). 저장소가 설정되지 않았으면 null.
	 *            주소는 조회마다 바뀌므로 앱은 이미지 캐시 키로 imageId를 쓴다.
	 */
	public record Image(UUID imageId, String url, Integer width, Integer height) {
	}

	/** @param name 읍면동 이름, @param shortName "시군구 읍면동" */
	public record Neighborhood(String id, String name, String shortName) {
	}

	/** 첨부된 체감 기록 요약: 체감 태그와 기록 당시 온도 민감도(FR-COM-05). 회원 ID는 없다. */
	public record AttachedFeelingRecord(UUID recordId, TemperatureTag temperature, HumidityTag humidity,
			WindTag wind, Instant recordedAt, TemperatureSensitivity temperatureSensitivity) {
	}

	public record Author(String nickname, TemperatureSensitivity temperatureSensitivity) {
	}

}
