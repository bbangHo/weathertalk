package kr.weathertalk.community.internal.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.community.internal.PostImageService;
import kr.weathertalk.member.Requester;
import kr.weathertalk.member.RequiresAgreedTerms;

/**
 * 게시글 이미지 업로드 (FR-COM-01): ① 업로드 요청 → 사전 서명 PUT 주소 ② 앱이 저장소에 직접 PUT ③ 업로드 확인 → 서버가 위치 등
 * 메타데이터를 지우고 다시 인코딩한 미첨부 이미지. 게시글 작성의 일부라 약관 재동의 전에는 막는다.
 */
@RestController
class ImageController {

	/** @param contentLength 올릴 파일 크기(바이트). 서명에 들어가므로 실제 PUT 본문 크기와 같아야 한다 */
	record UploadRequest(String contentType, Long contentLength) {
	}

	private final PostImageService images;

	ImageController(PostImageService images) {
		this.images = images;
	}

	@PostMapping("/api/v1/community/images")
	PostImageService.UploadTicket requestUpload(@RequiresAgreedTerms Requester.Member member,
			@RequestBody UploadRequest request) {
		return images.requestUpload(member.memberId(), request.contentType(), request.contentLength());
	}

	@PostMapping("/api/v1/community/images/{imageId}/complete")
	PostImageService.UploadedImage complete(@RequiresAgreedTerms Requester.Member member, @PathVariable UUID imageId) {
		return images.complete(member.memberId(), imageId);
	}

}
