package kr.weathertalk.outfit;

import java.util.List;

/** 옷차림 (04 옷 추천 §3): 겉옷·상의·하의·소품 목록과 한 줄 안내 문구. 빈 목록은 "없음"이다. */
public record Outfit(List<String> outer, List<String> top, List<String> bottom, List<String> accessories,
		String guide) {
}
