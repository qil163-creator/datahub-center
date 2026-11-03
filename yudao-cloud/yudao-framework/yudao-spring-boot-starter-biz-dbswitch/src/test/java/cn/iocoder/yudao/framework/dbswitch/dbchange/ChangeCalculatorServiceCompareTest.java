package cn.iocoder.yudao.framework.dbswitch.dbchange;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChangeCalculatorService#typeCompare} 的回归测试
 * <p>
 * 覆盖本次重构修复的三类正确性缺陷：
 * 1. 数值统一转 double 导致大整数/高精度小数精度丢失（变化漏检）；
 * 2. LocalDate 走带时分秒的格式化器抛 UnsupportedTemporalTypeException；
 * 3. java.time 与 java.sql 混合类型无法直接比较
 */
class ChangeCalculatorServiceCompareTest {

    @Test void nativeTimeDurationsCompareWithoutClockWrappingOrFractionLoss() {
        assertTrue(calculator.typeCompare(Types.TIME,"-838:59:59","00:00:00") < 0);
        assertTrue(calculator.typeCompare(Types.TIME,"123:45:56.000001","123:45:56.000002") < 0);
        assertTrue(calculator.typeCompare(Types.TIME,"-123:45:56.000001","-123:45:56.000002") > 0);
        assertEquals(0,calculator.typeCompare(Types.TIME,"03:45:56.123","003:45:56.123000"));
        assertEquals(0,calculator.typeCompare(Types.TIME,"-00:00:00.000000","00:00:00"));
    }

    @Test void malformedAndMixedTemporalDomainsDoNotBecomeEqual() {
        for(String invalid:java.util.List.of("3:00:00","01:60:00","01:00:60","01:00:00.1234567",
                " 01:00:00","01:00:00Z","+01:00:00","1000:00:00"))
            assertThrows(IllegalArgumentException.class,()->calculator.typeCompare(Types.TIME,invalid,"00:00:00"));
        assertThrows(IllegalArgumentException.class,()->calculator.typeCompare(Types.TIME,"03:00:00",LocalTime.of(3,0)));
        assertThrows(IllegalArgumentException.class,()->calculator.typeCompare(Types.TIME,Time.valueOf("03:00:00"),"03:00:00"));
    }

    @Test void nativePayloadChangesAndAbsentFieldsCannotDisappearThroughScalarCoercion() {
        var integer = new cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue("TEST_V1", new byte[]{16, 1}, new byte[]{1});
        var decimal = new cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue("TEST_V1", new byte[]{19, 1}, new byte[]{1});
        assertNotEquals(0, calculator.typeCompare(Types.OTHER, integer, decimal));
        assertThrows(IllegalArgumentException.class, () -> calculator.typeCompare(Types.OTHER, integer, 1));
        var missing = cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue.INSTANCE;
        var nativeNull = new cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue("TEST_V1", new byte[]{10}, new byte[]{10});
        assertNotEquals(0, calculator.typeCompare(Types.OTHER, missing, null));
        assertNotEquals(0, calculator.typeCompare(Types.OTHER, missing, nativeNull));
        assertEquals(0, calculator.typeCompare(Types.OTHER, missing, missing));
    }

    @Test void civilTimestampComparisonNeverRunsThroughTheJvmDstRules() {
        var previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            var absentWallTime = LocalDateTime.parse("2026-03-08T02:30:00.123456789");
            var laterWallTime = LocalDateTime.parse("2026-03-08T03:30:00.123456789");
            assertTrue(calculator.typeCompare(Types.TIMESTAMP, absentWallTime, laterWallTime) < 0);
            assertEquals(0, calculator.typeCompare(Types.TIMESTAMP_WITH_TIMEZONE,
                    java.time.OffsetDateTime.parse("2026-10-03T12:00:00.123456789+08:00"),
                    java.time.Instant.parse("2026-10-03T04:00:00.123456789Z")));
        } finally { java.util.TimeZone.setDefault(previous); }
    }

    @Test void uuidAndDetachedArraysHaveExplicitExactComparison() {
        var uuid = java.util.UUID.randomUUID();
        assertEquals(0, calculator.typeCompare(Types.OTHER, uuid, java.util.UUID.fromString(uuid.toString())));
        var left = new cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue("numeric", Types.NUMERIC,
                java.util.List.of(new BigDecimal("9007199254740993.000000001")));
        var right = new cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue("numeric", Types.NUMERIC,
                java.util.List.of(new BigDecimal("9007199254740993.000000002")));
        assertTrue(calculator.typeCompare(Types.ARRAY, left, right) < 0);
    }

	private final ChangeCalculatorService calculator = new ChangeCalculatorService();

	@Test
	void nullOrderingShouldBeStable() {
		assertEquals(0, calculator.typeCompare(Types.VARCHAR, null, null));
		assertEquals(-1, calculator.typeCompare(Types.VARCHAR, null, "a"));
		assertEquals(1, calculator.typeCompare(Types.VARCHAR, "a", null));
	}

	@Test
	void stringCompareShouldWork() {
		assertTrue(calculator.typeCompare(Types.VARCHAR, "a", "b") < 0);
		assertEquals(0, calculator.typeCompare(Types.VARCHAR, "same", "same"));
	}

	@Test
	void numericCompareShouldNotLoseLongPrecision() {
		// 2^53 与 2^53+1 转 double 后相等（历史实现会漏检该变化），BigDecimal 精确比较必须能区分
		long base = 1L << 53;
		assertNotEquals(0, calculator.typeCompare(Types.NUMERIC, base, base + 1));
		assertEquals(0, calculator.typeCompare(Types.NUMERIC, base, base));
	}

	@Test
	void numericCompareShouldTreatEqualValueDifferentScaleAsSame() {
		// 1.10 与 1.1 数值相等，不应误报为变化
		assertEquals(0, calculator.typeCompare(Types.DECIMAL,
				new BigDecimal("1.10"), new BigDecimal("1.1")));
		assertNotEquals(0, calculator.typeCompare(Types.DECIMAL,
				new BigDecimal("1.10"), new BigDecimal("1.11")));
	}

	@Test
	void numericCompareShouldHandleMixedNumberTypes() {
		assertEquals(0, calculator.typeCompare(Types.NUMERIC, 12, 12L));
		assertEquals(0, calculator.typeCompare(Types.NUMERIC, new BigDecimal("12"), 12));
	}

	@Test
	void localDateShouldNotThrowAndCompareCorrectly() {
		// 历史实现：LocalDate 走 "yyyy-MM-dd HH:mm:ss" 格式化器直接抛 UnsupportedTemporalTypeException
		assertDoesNotThrow(() -> calculator.typeCompare(Types.DATE,
				LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2)));
		assertEquals(0, calculator.typeCompare(Types.DATE,
				LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2)));
		assertTrue(calculator.typeCompare(Types.DATE,
				LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)) < 0);
	}

	@Test
	void mixedJavaTimeAndJavaSqlDateTypesShouldCompareEqual() {
		assertEquals(0, calculator.typeCompare(Types.DATE,
				LocalDate.of(2024, 1, 2), Date.valueOf("2024-01-02")));
		LocalDateTime ldt = LocalDateTime.of(2024, 1, 2, 10, 30, 0);
		assertEquals(0, calculator.typeCompare(Types.TIMESTAMP,
				ldt, Timestamp.valueOf(ldt)));
		assertEquals(0, calculator.typeCompare(Types.TIME,
				LocalTime.of(10, 30, 0), Time.valueOf("10:30:00")));
	}

	@Test
	void booleanAndIntegerMixShouldCompareEqual() {
		assertEquals(0, calculator.typeCompare(Types.BOOLEAN, Boolean.TRUE, 1));
		assertEquals(0, calculator.typeCompare(Types.BOOLEAN, Boolean.FALSE, 0));
		assertNotEquals(0, calculator.typeCompare(Types.BOOLEAN, Boolean.TRUE, 0));
		assertEquals(0, calculator.typeCompare(Types.TINYINT, 1, Boolean.TRUE));
	}

	@Test
	void unsignedBigintShouldNotWrapIntoSignedLong() {
		BigInteger unsignedMax = new BigInteger("18446744073709551615");
		BigInteger aboveSignedMax = BigInteger.ONE.shiftLeft(63);
		assertTrue(calculator.typeCompare(Types.BIGINT, unsignedMax, -1L) > 0);
		assertTrue(calculator.typeCompare(Types.BIGINT, aboveSignedMax, Long.MAX_VALUE) > 0);
		assertTrue(calculator.typeCompare(Types.BIGINT, unsignedMax, unsignedMax.subtract(BigInteger.ONE)) > 0);
		assertEquals(0, calculator.typeCompare(Types.BIGINT, unsignedMax, new BigDecimal(unsignedMax)));
		assertTrue(calculator.typeCompare(Types.BIGINT, BigInteger.ONE.shiftLeft(64), 0L) > 0);
	}

	@Test
	void integerAndBooleanComparisonShouldNotDiscardFractionalDifferences() {
		assertNotEquals(0, calculator.typeCompare(Types.INTEGER, new BigDecimal("1.5"), 1));
		assertNotEquals(0, calculator.typeCompare(Types.BOOLEAN, new BigDecimal("0.5"), false));
		assertEquals(0, calculator.typeCompare(Types.INTEGER, BigInteger.ONE, true));
	}

	@Test
	void localTimeComparisonShouldRetainNanoseconds() {
		LocalTime earlier = LocalTime.of(10, 30, 0, 123_456_788);
		LocalTime later = earlier.plusNanos(1);
		assertTrue(calculator.typeCompare(Types.TIME, earlier, later) < 0);
		assertTrue(calculator.typeCompare(Types.TIME, later, earlier) > 0);
		assertEquals(0, calculator.typeCompare(Types.TIME, later, later));
	}

	@Test
	void mixedSqlTimeComparisonShouldRetainStoredMilliseconds() {
		Time jdbcTime = Time.valueOf("10:30:00");
		jdbcTime.setTime(jdbcTime.getTime() + 123);
		LocalTime same = LocalTime.of(10, 30, 0, 123_000_000);
		assertEquals(0, calculator.typeCompare(Types.TIME, same, jdbcTime));
		assertEquals(0, calculator.typeCompare(Types.TIME, jdbcTime, same));
		assertTrue(calculator.typeCompare(Types.TIME, same.plusNanos(1), jdbcTime) > 0);
	}

	@Test
	void unsupportedBinaryValueShouldFailInsteadOfComparingEqual() {
		assertThrows(IllegalArgumentException.class,
				() -> calculator.typeCompare(Types.OTHER, new Object(), new Object()));
		assertEquals(0, calculator.typeCompare(Types.VARBINARY, new byte[]{1, 2}, new byte[]{1, 2}));
		assertNotEquals(0, calculator.typeCompare(Types.VARBINARY, new byte[]{1, 2}, new byte[]{1, 3}));
	}
}
