package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class LockedReplayColumnTest {
    @Test void integerDomainsNeverRoundFractionalOrOverflowingInput() throws Exception {
        var column = new LockedReplayColumn("int", Types.INTEGER, false, CursorKeyContract.Domain.integer(32, false), 0, false);
        assertEquals(Integer.MIN_VALUE, column.exact(new BigDecimal(Integer.MIN_VALUE)));
        assertEquals(Integer.MAX_VALUE, column.exact(BigInteger.valueOf(Integer.MAX_VALUE)));
        for (Object value : new Object[]{new BigDecimal("1.1"), 2147483648L, -2147483649L, 1d, "1", null})
            assertThrows(SQLException.class, () -> column.exact(value));
    }
    @Test void decimalRangeAndCursorTypesAreExact() throws Exception {
        var column = new LockedReplayColumn("decimal(38,6)", Types.DECIMAL, false, CursorKeyContract.Domain.decimal(38, 6, false), 0, false);
        var value = new BigDecimal("99999999999999999999999999999999.999999");
        assertEquals(value, column.exact(value)); assertEquals(value.negate(), column.exact(value.negate()));
        assertThrows(SQLException.class, () -> column.exact(new BigDecimal("1E32")));
        assertThrows(SQLException.class, () -> column.exact(new BigDecimal("0.0000001")));
        var cursor = column.cursor(value); column.validate(cursor);
        assertThrows(SQLException.class, () -> column.validate(TypedCursorValue.of(Types.DECIMAL, "decimal(38,5)", value)));
    }
    @Test void characterContractsRejectEncodingLossPaddingAndMalformedUnicode() throws Exception {
        var ascii = new LockedReplayColumn("varchar(3)", Types.VARCHAR, true, null, 3, true);
        assertNull(ascii.exact(null)); assertEquals(" a ", ascii.exact(" a "));
        assertThrows(SQLException.class, () -> ascii.exact("界")); assertThrows(SQLException.class, () -> ascii.exact("four"));
        assertThrows(SQLException.class, ascii::requireOrdered);
        var unicode = new LockedReplayColumn("nvarchar(3)", Types.NVARCHAR, true, null, 3, false);
        assertEquals("😀a", unicode.exact("😀a"));
        assertThrows(SQLException.class, () -> unicode.exact("\uD800")); assertThrows(SQLException.class, () -> unicode.exact("\uDC00"));
        assertThrows(SQLException.class, () -> unicode.exact("😀ab"));
    }
}
