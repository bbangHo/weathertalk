package kr.weathertalk.member.internal;

import java.security.SecureRandom;
import java.util.List;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

/**
 * 날씨 형용사 + 날씨 명사 + 1~9999 숫자 (예: 포근한구름127). 최대 10자라 회원이 바꾸는 닉네임 규칙(한글·영문·숫자, 2~10자)과도 맞는다.
 */
@Component
class RandomNicknameGenerator implements NicknameGenerator {

	private static final List<String> ADJECTIVES = List.of("포근한", "상쾌한", "맑은", "시원한", "따뜻한", "산뜻한", "촉촉한",
			"선선한", "화창한", "보송한", "아늑한", "싱그런");

	private static final List<String> NOUNS = List.of("구름", "바람", "햇살", "이슬", "소나기", "무지개", "눈송이", "안개", "별빛",
			"노을", "하늘", "봄비");

	private final RandomGenerator random = new SecureRandom();

	@Override
	public String generate() {
		return ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + NOUNS.get(random.nextInt(NOUNS.size()))
				+ (random.nextInt(9999) + 1);
	}

}
