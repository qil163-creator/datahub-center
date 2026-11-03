package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link OlapStreamLoadHelper} JSON 值规整逻辑的单元测试
 */
class OlapStreamLoadHelperTest {

	@Test
	void normalizeShouldKeepNullAndPrimitives() {
		assertNull(OlapStreamLoadHelper.normalizeJsonValue(null));
		assertEquals(123L, OlapStreamLoadHelper.normalizeJsonValue(123L));
		assertEquals("abc", OlapStreamLoadHelper.normalizeJsonValue("abc"));
		assertEquals(Boolean.TRUE, OlapStreamLoadHelper.normalizeJsonValue(Boolean.TRUE));
		byte[] bytes = new byte[]{1, 2};
		assertSame(bytes, OlapStreamLoadHelper.normalizeJsonValue(bytes));
	}

	@Test
	void normalizeShouldStringifyDateTimeTypes() {
		Timestamp ts = Timestamp.valueOf("2024-01-02 10:30:00");
		assertEquals(ts.toString(), OlapStreamLoadHelper.normalizeJsonValue(ts));
		assertEquals("2024-01-02", OlapStreamLoadHelper.normalizeJsonValue(java.sql.Date.valueOf("2024-01-02")));
		assertEquals("10:30:00", OlapStreamLoadHelper.normalizeJsonValue(java.sql.Time.valueOf("10:30:00")));
		assertEquals("2024-01-02 10:30:00",
				OlapStreamLoadHelper.normalizeJsonValue(LocalDateTime.of(2024, 1, 2, 10, 30, 0)));
		assertEquals("2024-01-02", OlapStreamLoadHelper.normalizeJsonValue(LocalDate.of(2024, 1, 2)));
	}

	@ParameterizedTest
	@CsvSource({"123000000, .123", "123456000, .123456", "123456789, .123456789", "1, .000000001"})
	void localAndJdbcTimestampsRetainEveryFractionDigit(int nanos, String fraction) {
		LocalDateTime value = LocalDateTime.of(2026, 1, 2, 10, 30, 0, nanos);
		assertEquals("2026-01-02 10:30:00" + fraction, OlapStreamLoadHelper.normalizeJsonValue(value));
		assertEquals("2026-01-02 10:30:00" + fraction, OlapStreamLoadHelper.normalizeJsonValue(Timestamp.valueOf(value)));
	}
}
