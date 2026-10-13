package kr.weathertalk.community.internal.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.weathertalk.community.internal.AdminDeletionService;
import kr.weathertalk.member.Requester;

/**
 * 관리자 삭제 (FR-COM-15, ADR 0012). 앱 화면이 없는 관리자 전용 명령이다. 관리자 여부는 요청자 해석 결과로만 판정한다.
 * 이미 없는 대상도 204(멱등). 관리자 명령은 약관 재동의 가드를 두지 않는다.
 */
@RestController
class AdminDeletionController {

	record DeletionRequest(AdminDeletionService.TargetType targetType, UUID targetId, String reason) {
	}

	private final AdminDeletionService deletions;

	AdminDeletionController(AdminDeletionService deletions) {
		this.deletions = deletions;
	}

	@PostMapping("/api/v1/admin/community/deletions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(Requester.Member member, @RequestBody DeletionRequest request) {
		deletions.delete(member, request.targetType(), request.targetId(), request.reason());
	}

}
