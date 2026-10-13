package kr.weathertalk.weather.internal.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Function;

import kr.weathertalk.weather.PrecipitationAmount;

/** JDBC 값 변환 공통. 결측은 SQL NULL로 두고 0으로 채우지 않는다. */
final class StoreSupport {

	private StoreSupport() {
	}

	static Timestamp ts(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	static Double dbl(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	static <E extends Enum<E>> E enumValue(ResultSet rs, String column, Function<String, E> valueOf)
			throws SQLException {
		String value = rs.getString(column);
		return value == null ? null : valueOf.apply(value);
	}

	static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	/** 구간 세 컬럼(prefix_lower, prefix_upper, prefix_lower_exclusive) → 구간. 하한이 NULL이면 결측. */
	static PrecipitationAmount amount(ResultSet rs, String prefix, String unit) throws SQLException {
		Double lower = dbl(rs, prefix + "_lower");
		if (lower == null) {
			return null;
		}
		return PrecipitationAmount.of(lower, dbl(rs, prefix + "_upper"), rs.getBoolean(prefix + "_lower_exclusive"),
				unit);
	}

	static Double lower(PrecipitationAmount amount) {
		return amount == null ? null : amount.lower();
	}

	static Double upper(PrecipitationAmount amount) {
		return amount == null ? null : amount.upper();
	}

	static Boolean exclusive(PrecipitationAmount amount) {
		return amount == null ? null : amount.lowerExclusive();
	}

}
