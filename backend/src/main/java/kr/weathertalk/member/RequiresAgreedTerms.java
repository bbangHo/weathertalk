package kr.weathertalk.member;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러의 {@link Requester.Member} 파라미터에 붙이면, 필수 약관 재동의가 필요한 회원을 TERMS_REAGREEMENT_REQUIRED로 막는다.
 * 약관 재동의 전에도 허용되는 쓰기(본인 글 삭제, 민감도 변경, 탈퇴 등 — contracts.md §4)에는 붙이지 않는다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAgreedTerms {
}
