package kr.weathertalk.member.internal;

/**
 * 가입 시 부여할 무작위 닉네임 후보를 만든다 (BR-MEM-08). 유일성 확인과 재시도는 호출자가 한다.
 * 생성 규칙(단어 사전·숫자 접미사)은 미결 01 §9 #1 — 확정되면 구현만 바꾼다.
 */
public interface NicknameGenerator {

	String generate();

}
