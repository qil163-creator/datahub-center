package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPushIncrementTypesTest {

    @Test
    @DisplayName("JDBC 数值与时间类型可用作增量字段")
    void jdbcNumericAndTemporalSupported() {
        assertTrue(DataPushIncrementTypes.supported(Types.BIGINT, null));
        assertTrue(DataPushIncrementTypes.supported(Types.DECIMAL, null));
        assertTrue(DataPushIncrementTypes.supported(Types.TIMESTAMP, null));
        assertTrue(DataPushIncrementTypes.supported(Types.TIMESTAMP_WITH_TIMEZONE, null));
        assertTrue(DataPushIncrementTypes.supported(Types.DATE, null));
    }

    @Test
    @DisplayName("字符串、布尔、UUID 不可用作增量字段")
    void rejectStringBooleanUuid() {
        assertFalse(DataPushIncrementTypes.supported(Types.VARCHAR, "varchar"));
        assertFalse(DataPushIncrementTypes.supported(Types.BOOLEAN, "bool"));
        assertFalse(DataPushIncrementTypes.supported(Types.OTHER, "uuid"));
        assertFalse(DataPushIncrementTypes.supported(Types.OTHER, "jsonb"));
    }

    @Test
    @DisplayName("JDBC 未知时回退类型名：SERIAL / NUMBER 可用，INTERVAL 不可用")
    void fallbackTypeName() {
        assertTrue(DataPushIncrementTypes.supported(null, "bigserial"));
        assertTrue(DataPushIncrementTypes.supported(Types.OTHER, "NUMBER"));
        assertFalse(DataPushIncrementTypes.supported(null, "interval"));
        assertFalse(DataPushIncrementTypes.supported(null, "nvarchar2"));
    }

}
