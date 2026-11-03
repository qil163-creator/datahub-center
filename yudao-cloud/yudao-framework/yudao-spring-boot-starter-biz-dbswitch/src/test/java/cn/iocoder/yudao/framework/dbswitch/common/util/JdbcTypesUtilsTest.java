package cn.iocoder.yudao.framework.dbswitch.common.util;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcTypesUtils} 字节估算与类型判断的单元测试
 */
class JdbcTypesUtilsTest {

	@Test
	void getObjectSizeShouldHandleCommonJavaTypes() {
		assertEquals(0, JdbcTypesUtils.getObjectSize(Types.VARCHAR, null));
		assertEquals(5, JdbcTypesUtils.getObjectSize(Types.BINARY, new byte[5]));
		assertEquals(2L * 4, JdbcTypesUtils.getObjectSize(Types.VARCHAR, "abcd"));
		assertEquals(8, JdbcTypesUtils.getObjectSize(Types.BIGINT, 123L));
		assertEquals(8, JdbcTypesUtils.getObjectSize(Types.TIMESTAMP, new Timestamp(0)));
		assertEquals(8, JdbcTypesUtils.getObjectSize(Types.TIMESTAMP, java.time.LocalDateTime.now()));
		assertEquals(1, JdbcTypesUtils.getObjectSize(Types.BOOLEAN, Boolean.TRUE));
	}

	@Test
	void getRecordSizeShouldSumByJdbcTypes() {
		Object[] record = {"ab", 1L, null};
		int[] types = {Types.VARCHAR, Types.BIGINT, Types.VARCHAR};
		assertEquals(2L * 2 + 8, JdbcTypesUtils.getRecordSize(record, types));
	}

	@Test
	void getRecordSizeShouldEstimateWithoutJdbcTypes() {
		Object[] record = {"ab", 1L, Boolean.FALSE, new byte[3], null};
		assertEquals(2L * 2 + 8 + 1 + 3, JdbcTypesUtils.getRecordSize(record));
	}

	@Test
	void getRecordSizeShouldReturnZeroOnLengthMismatch() {
		assertEquals(0, JdbcTypesUtils.getRecordSize(new Object[]{1}, new int[]{Types.BIGINT, Types.BIGINT}));
		assertEquals(0, JdbcTypesUtils.getRecordSize(null, null));
	}

	@Test
	void isIncrementShouldAcceptIntegerAndDateTimeTypes() {
		assertTrue(JdbcTypesUtils.isIncrement(Types.BIGINT));
		assertTrue(JdbcTypesUtils.isIncrement(Types.INTEGER));
		assertTrue(JdbcTypesUtils.isIncrement(Types.TIMESTAMP));
		assertTrue(JdbcTypesUtils.isIncrement(Types.DATE));
		assertFalse(JdbcTypesUtils.isIncrement(Types.VARCHAR));
		assertFalse(JdbcTypesUtils.isIncrement(Types.DECIMAL));
	}
}
