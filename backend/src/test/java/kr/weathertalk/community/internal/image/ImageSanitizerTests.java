package kr.weathertalk.community.internal.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** 사진 축소·방향 반영 (FR-COM-01 규칙 3, ADR 0009의 다시 인코딩 전 처리). */
class ImageSanitizerTests {

	@Test
	void fitShrinksLongSideOnlyWhenLarger() {
		BufferedImage wide = new BufferedImage(5000, 1000, BufferedImage.TYPE_INT_RGB);
		BufferedImage fitted = ImageSanitizer.fit(wide, 2048);
		assertThat(fitted.getWidth()).isEqualTo(2048);
		assertThat(fitted.getHeight()).isEqualTo(410);
		BufferedImage small = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
		assertThat(ImageSanitizer.fit(small, 2048)).isSameAs(small);
	}

	@Test
	void orientationRotatesAndFlipsPixels() {
		BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0xFF0000);
		for (int orientation = 1; orientation <= 8; orientation++) {
			BufferedImage out = ImageSanitizer.applyOrientation(image, orientation);
			boolean swapped = orientation >= 5;
			assertThat(out.getWidth()).isEqualTo(swapped ? 20 : 30);
			assertThat(out.getHeight()).isEqualTo(swapped ? 30 : 20);
		}
		// 왼쪽 위 점의 도착 위치(TIFF Orientation 정의)
		assertThat(red(ImageSanitizer.applyOrientation(image, 2), 29, 0)).isTrue();
		assertThat(red(ImageSanitizer.applyOrientation(image, 3), 29, 19)).isTrue();
		assertThat(red(ImageSanitizer.applyOrientation(image, 4), 0, 19)).isTrue();
		assertThat(red(ImageSanitizer.applyOrientation(image, 5), 0, 0)).isTrue();
		assertThat(red(ImageSanitizer.applyOrientation(image, 6), 19, 0)).isTrue();
		assertThat(red(ImageSanitizer.applyOrientation(image, 7), 19, 29)).isTrue();
		assertThat(red(ImageSanitizer.applyOrientation(image, 8), 0, 29)).isTrue();
	}

	private static boolean red(BufferedImage image, int x, int y) {
		return (image.getRGB(x, y) & 0xFFFFFF) == 0xFF0000;
	}

}
