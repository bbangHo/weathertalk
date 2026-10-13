package kr.weathertalk.notification.internal;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import kr.weathertalk.feeling.DerivationSource;
import kr.weathertalk.feeling.TemperatureTag;
import kr.weathertalk.member.TemperatureSensitivity;
import kr.weathertalk.notification.internal.Dispatcher.AlertMessage;
import kr.weathertalk.notification.internal.config.NotificationProperties;
import kr.weathertalk.shared.time.TimeConfig;
import kr.weathertalk.weather.PrecipitationType;
import kr.weathertalk.weather.SkyCondition;

/**
 * 알림 문구 (06 §4.1). 모든 가변값은 표시명 표({@link #skyName} 등)를 거치고, 표시명이 없으면 그 줄을 뺀다 — 내부 코드를 그대로 내보내지
 * 않는다(legacy 05-#8). 회원이 쓴 글·닉네임은 어떤 알림에도 넣지 않는다. 제목·본문이 길면 본문 뒤 줄부터 뺀다. 고정폭 정렬은 하지 않는다.
 */
@Component
public class MessageTemplates {

	/** 정기 날씨 알림 한 동네·한 민감도의 재료. 없는 값은 null이며 그 줄만 빠진다. */
	public record DailyContent(String neighborhoodName, Double temperature, SkyCondition sky, Double minTemperature,
			Double maxTemperature, TemperatureSensitivity sensitivity, TemperatureTag temperatureTag,
			DerivationSource source, String outfitLine, String comparisonPhrase, Instant precipitationAt,
			PrecipitationType precipitationType) {
	}

	private final int titleMax;
	private final int bodyMax;

	MessageTemplates(NotificationProperties properties) {
		this.titleMax = properties.message().titleMaxLength();
		this.bodyMax = properties.message().bodyMaxLength();
	}

	/** ① 정기 날씨 알림. 이동 대상: 홈(해당 동네). */
	public AlertMessage daily(String neighborhoodId, DailyContent c) {
		List<String> lines = new ArrayList<>();
		lines.add(weatherLine(c));
		lines.add(feelingLine(c.sensitivity(), c.temperatureTag(), c.source()));
		lines.add(c.outfitLine() == null || c.outfitLine().isBlank() ? null : "👕 " + c.outfitLine().strip());
		String rain = rainLine(c.precipitationAt(), c.precipitationType());
		String last = String.join(" ", nonNull(c.comparisonPhrase(), rain));
		lines.add(last.isBlank() ? null : last);
		return new AlertMessage(NotificationKind.DAILY_WEATHER, "daily-weather.v1", title(c.neighborhoodName() + " 오늘 날씨"),
				body(lines), neighborhoodId);
	}

	/** ② 강수 예보 알림. 이동 대상: 홈의 시간별 예보(해당 동네). */
	public AlertMessage precipitationForecast(String neighborhoodId, String neighborhoodName, PrecipitationType type,
			String phrase, Instant expectedStartAt) {
		String typeName = forecastPrecipitationName(type);
		if (typeName == null) {
			return null;
		}
		String emoji = type == PrecipitationType.SNOW ? "❄" : "☔";
		String what = phrase == null || phrase.isBlank() ? typeName : phrase.strip();
		return new AlertMessage(NotificationKind.PRECIPITATION_FORECAST, "precipitation-forecast.v1",
				title(neighborhoodName + " " + typeName + " 소식 " + emoji),
				body(List.of(timeName(expectedStartAt) + "쯤부터 " + what + " 예보가 있어요. 우산 챙기세요.")),
				neighborhoodId);
	}

	/** ③ 현장 제보 강수 알림(비·눈 — 강수 형태로 문구만 구분). 이동 대상: 홈의 현장 제보 현황(해당 동네). */
	public AlertMessage fieldReportPrecipitation(String neighborhoodId, String neighborhoodName, PrecipitationType type,
			int reporterCount) {
		boolean snow = type == PrecipitationType.SNOW;
		if (type != PrecipitationType.RAIN && !snow) {
			return null;
		}
		String title = neighborhoodName + (snow ? "에 지금 눈이 온대요 ❄" : "에 지금 비가 온대요 ☔");
		String body = "이웃 " + reporterCount + "명이 지금 " + (snow ? "눈이" : "비가") + " 온다고 알려줬어요.";
		return new AlertMessage(NotificationKind.FIELD_REPORT_PRECIPITATION,
				snow ? "field-report-snow.v1" : "field-report-rain.v1", title(title), body(List.of(body)),
				neighborhoodId);
	}

	/** ④ 댓글 알림. 게시글 작성자 문구 / 상위 댓글 작성자(게시글 작성자 아님) 문구. 이동 대상: 게시글 상세. */
	public AlertMessage comment(String postId, boolean toPostAuthor) {
		return toPostAuthor
				? new AlertMessage(NotificationKind.COMMENT, "comment-on-post.v1", title("내 글에 새 댓글이 달렸어요"),
						body(List.of("이웃이 내 글에 댓글을 남겼어요. 눌러서 확인해 보세요.")), postId)
				: new AlertMessage(NotificationKind.COMMENT, "reply-on-comment.v1", title("내 댓글에 답글이 달렸어요"),
						body(List.of("이웃이 내 댓글에 답글을 남겼어요. 눌러서 확인해 보세요.")), postId);
	}

	private String weatherLine(DailyContent c) {
		List<String> head = new ArrayList<>();
		if (c.temperature() != null) {
			String sky = skyName(c.sky());
			head.add(round(c.temperature()) + "°" + (sky == null ? "" : " " + sky));
		}
		else if (skyName(c.sky()) != null) {
			head.add(skyName(c.sky()));
		}
		if (c.minTemperature() != null && c.maxTemperature() != null) {
			head.add("최저 " + round(c.minTemperature()) + "° / 최고 " + round(c.maxTemperature()) + "°");
		}
		return head.isEmpty() ? null : String.join(" · ", head);
	}

	private static String feelingLine(TemperatureSensitivity sensitivity, TemperatureTag tag, DerivationSource source) {
		String who = sensitivityName(sensitivity);
		String feeling = temperatureTagName(tag);
		if (who == null || feeling == null || source == null) {
			return null;
		}
		return switch (source) {
			case NEIGHBORHOOD_FEELING -> who + " 이웃들은 지금 '" + feeling + "'이라고 해요";
			case FEELING_MAPPING, REFERENCE_TABLE -> who + "에겐 '" + feeling + "'으로 느껴질 거예요";
		};
	}

	private static String rainLine(Instant at, PrecipitationType type) {
		String name = forecastPrecipitationName(type);
		if (at == null || name == null) {
			return null;
		}
		return timeName(at) + "쯤 " + name + " 소식이 있어요";
	}

	// ── 표시명 표 (값마다 반드시 있어야 한다 — 테스트로 강제) ──

	static String skyName(SkyCondition sky) {
		if (sky == null) {
			return null;
		}
		return switch (sky) {
			case CLEAR -> "맑음";
			case MOSTLY_CLOUDY -> "구름많음";
			case OVERCAST -> "흐림";
		};
	}

	/** 강수 예보·정기 알림의 강수 형태 표시명. 비·눈은 "비나 눈"(06 §4.1 ②). 없음은 표시하지 않는다. */
	static String forecastPrecipitationName(PrecipitationType type) {
		if (type == null) {
			return null;
		}
		return switch (type) {
			case NONE -> null;
			case RAIN -> "비";
			case RAIN_SNOW -> "비나 눈";
			case SNOW -> "눈";
			case SHOWER -> "소나기";
		};
	}

	static String temperatureTagName(TemperatureTag tag) {
		if (tag == null) {
			return null;
		}
		return switch (tag) {
			case VERY_COLD -> "매우 추움";
			case COLD -> "추움";
			case SLIGHTLY_COLD -> "조금 추움";
			case JUST_RIGHT -> "딱 좋음";
			case SLIGHTLY_HOT -> "조금 더움";
			case HOT -> "더움";
			case VERY_HOT -> "매우 더움";
		};
	}

	static String sensitivityName(TemperatureSensitivity sensitivity) {
		if (sensitivity == null) {
			return null;
		}
		return switch (sensitivity) {
			case HEAT_SENSITIVE -> "더위를 많이 타는 분";
			case NORMAL -> "보통 체질인 분";
			case COLD_SENSITIVE -> "추위를 많이 타는 분";
		};
	}

	/** "오후 3시", "오후 3시 30분" (Asia/Seoul). */
	static String timeName(Instant at) {
		LocalTime time = at.atZone(TimeConfig.SEOUL).toLocalTime();
		int hour = time.getHour();
		String half = hour < 12 ? "오전" : "오후";
		int h12 = hour % 12 == 0 ? 12 : hour % 12;
		return half + " " + h12 + "시" + (time.getMinute() == 0 ? "" : " " + time.getMinute() + "분");
	}

	private String title(String title) {
		return title.length() <= titleMax ? title : title.substring(0, titleMax);
	}

	/** 빈 줄은 빼고, 길면 뒤 줄부터 뺀다. */
	private String body(List<String> lines) {
		List<String> kept = new ArrayList<>(lines.stream().filter(Objects::nonNull).filter(l -> !l.isBlank()).toList());
		String body = String.join("\n", kept);
		while (body.length() > bodyMax && kept.size() > 1) {
			kept.remove(kept.size() - 1);
			body = String.join("\n", kept);
		}
		return body.length() <= bodyMax ? body : body.substring(0, bodyMax);
	}

	private static long round(double value) {
		return Math.round(value);
	}

	private static List<String> nonNull(String... values) {
		List<String> list = new ArrayList<>();
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				list.add(value);
			}
		}
		return list;
	}

}
