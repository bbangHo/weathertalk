package kr.weathertalk.community.internal.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import kr.weathertalk.community.internal.config.CommunityProperties;

/**
 * 업로드된 사진을 픽셀만 남기고 다시 인코딩한다 (FR-COM-01 규칙 2·3, ADR 0009).
 * <ul>
 * <li>원본의 메타데이터(EXIF GPS·XMP·제조사 노트·ICC 외 모든 덧붙은 블록)는 읽지 않고 버린다 — 새 파일에는 픽셀만 들어간다.</li>
 * <li>EXIF 방향 값은 버리기 전에 픽셀에 반영한다(안 하면 세로 사진이 눕는다).</li>
 * <li>긴 변이 community.image.max-dimension을 넘으면 줄인다. 아주 큰 사진은 디코딩 단계에서 미리 솎아(subsampling) 메모리를 아낀다.</li>
 * <li>투명도가 있으면 PNG, 없으면 JPEG(community.image.jpeg-quality)로 저장한다. WEBP는 순수 자바 인코더가 없어 이 둘로 바꾼다.</li>
 * </ul>
 */
@Component
public class ImageSanitizer {

	public record Sanitized(byte[] content, String contentType, int width, int height) {
	}

	/** 이미지로 해석할 수 없거나 허용 형식이 아니다. */
	public static class UnreadableImageException extends RuntimeException {

		UnreadableImageException(String message) {
			super(message);
		}

	}

	private final CommunityProperties properties;

	public ImageSanitizer(CommunityProperties properties) {
		this.properties = properties;
		// 실행 가능 jar에서는 ImageIO가 처음 쓰일 때의 클래스로더로 플러그인을 찾으므로 TwelveMonkeys 판독기를 명시적으로 등록한다.
		ImageIO.scanForPlugins();
		// 원본은 이미 메모리에 있으니 임시 파일 캐시를 쓰지 않는다(원본 사진이 디스크에 남지 않게)
		ImageIO.setUseCache(false);
		for (String type : properties.image().allowedTypes()) {
			if (!ImageIO.getImageReadersByMIMEType(type).hasNext()) {
				throw new IllegalStateException("community.image.allowed-types의 " + type
						+ " 형식을 읽을 수 있는 판독기가 없습니다. 서버가 디코딩할 수 없는 형식은 허용할 수 없습니다(메타데이터 제거 불가).");
			}
		}
	}

	public Sanitized sanitize(byte[] original) {
		BufferedImage decoded = decode(original);
		BufferedImage oriented = applyOrientation(decoded, orientation(original));
		BufferedImage resized = fit(oriented, properties.image().maxDimension());
		try {
			return resized.getColorModel().hasAlpha() ? png(resized) : jpeg(resized);
		}
		catch (IOException e) {
			throw new IllegalStateException("이미지 인코딩에 실패했습니다.", e);
		}
	}

	private BufferedImage decode(byte[] original) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
			Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
			if (readers == null || !readers.hasNext()) {
				throw new UnreadableImageException("이미지로 해석할 수 없는 파일입니다.");
			}
			ImageReader reader = readers.next();
			try {
				if (!allowed(reader)) {
					throw new UnreadableImageException("허용되지 않는 이미지 형식입니다.");
				}
				// 메타데이터는 읽지도 않는다(방향 값은 따로 읽음)
				reader.setInput(input, true, true);
				int longSide = Math.max(reader.getWidth(0), reader.getHeight(0));
				int step = Math.max(1, longSide / properties.image().maxDimension());
				ImageReadParam param = reader.getDefaultReadParam();
				param.setSourceSubsampling(step, step, 0, 0);
				BufferedImage image = reader.read(0, param);
				if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
					throw new UnreadableImageException("이미지로 해석할 수 없는 파일입니다.");
				}
				return image;
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof UnreadableImageException unreadable) {
				throw unreadable;
			}
			throw new UnreadableImageException("이미지로 해석할 수 없는 파일입니다.");
		}
	}

	private boolean allowed(ImageReader reader) {
		if (reader.getOriginatingProvider() == null) {
			return false;
		}
		List<String> allowed = properties.image().allowedTypes().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
		return Arrays.stream(reader.getOriginatingProvider().getMIMETypes())
			.map(t -> t.toLowerCase(Locale.ROOT))
			.anyMatch(allowed::contains);
	}

	/** EXIF 방향(1~8). 없거나 읽을 수 없으면 1(그대로). */
	private static int orientation(byte[] original) {
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original));
			ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
			if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
				return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
			}
		}
		catch (Exception e) {
			// 방향을 모르면 원래 방향대로 둔다
		}
		return 1;
	}

	/** EXIF 방향 값에 따라 픽셀을 돌리거나 뒤집는다(TIFF 6.0 Orientation 정의). */
	static BufferedImage applyOrientation(BufferedImage image, int orientation) {
		if (orientation < 2 || orientation > 8) {
			return image;
		}
		int w = image.getWidth();
		int h = image.getHeight();
		boolean swap = orientation >= 5;
		// (m00, m10, m01, m11, m02, m12): x' = m00·x + m01·y + m02, y' = m10·x + m11·y + m12
		AffineTransform t = switch (orientation) {
			case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0); // 좌우 뒤집기
			case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // 180도
			case 4 -> new AffineTransform(1, 0, 0, -1, 0, h); // 상하 뒤집기
			case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // 대각 뒤집기(x↔y)
			case 6 -> new AffineTransform(0, 1, -1, 0, h, 0); // 시계 방향 90도
			case 7 -> new AffineTransform(0, -1, -1, 0, h, w); // 반대 대각 뒤집기
			default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8: 반시계 방향 90도
		};
		BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, workingType(image));
		Graphics2D g = out.createGraphics();
		try {
			g.drawImage(image, t, null);
		}
		finally {
			g.dispose();
		}
		return out;
	}

	/** 긴 변을 maxDimension 이하로 줄인다. 한 번에 크게 줄이면 계단 현상이 생겨 절반씩 단계적으로 줄인다. */
	static BufferedImage fit(BufferedImage image, int maxDimension) {
		int longSide = Math.max(image.getWidth(), image.getHeight());
		if (longSide <= maxDimension) {
			return image;
		}
		double scale = (double) maxDimension / longSide;
		int targetW = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int targetH = Math.max(1, (int) Math.round(image.getHeight() * scale));
		BufferedImage current = image;
		int w = image.getWidth();
		int h = image.getHeight();
		do {
			w = Math.max(targetW, w / 2);
			h = Math.max(targetH, h / 2);
			BufferedImage next = new BufferedImage(w, h, workingType(current));
			Graphics2D g = next.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(current, 0, 0, w, h, null);
			}
			finally {
				g.dispose();
			}
			current = next;
		}
		while (w != targetW || h != targetH);
		return current;
	}

	private static int workingType(BufferedImage image) {
		return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
	}

	private Sanitized jpeg(BufferedImage image) throws IOException {
		BufferedImage rgb = image;
		if (image.getType() != BufferedImage.TYPE_INT_RGB) {
			rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			try {
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, image.getWidth(), image.getHeight());
				g.drawImage(image, 0, 0, null);
			}
			finally {
				g.dispose();
			}
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(properties.image().jpegQuality().floatValue());
			// 메타데이터 없이 픽셀만 쓴다
			writer.write(null, new IIOImage(rgb, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return new Sanitized(bytes.toByteArray(), "image/jpeg", rgb.getWidth(), rgb.getHeight());
	}

	private static Sanitized png(BufferedImage image) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			writer.write(null, new IIOImage(image, null, null), null);
		}
		finally {
			writer.dispose();
		}
		return new Sanitized(bytes.toByteArray(), "image/png", image.getWidth(), image.getHeight());
	}

}
