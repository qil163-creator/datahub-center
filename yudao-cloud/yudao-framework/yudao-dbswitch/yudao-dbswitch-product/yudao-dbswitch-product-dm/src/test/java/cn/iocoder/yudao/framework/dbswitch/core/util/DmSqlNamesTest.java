package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DmSqlNamesTest {
    @Test void namesPreserveCaseAndPunctuationAndEscapeOnlyDelimiter() {
        assertEquals("\"mixedCase\"",DmSqlNames.quote("mixedCase"));
        assertEquals("\"name\"\"';(,\"",DmSqlNames.quote("name\"';(,"));
        assertEquals("\"sch\".\"tab\"\"\"",DmSqlNames.relation("sch","tab\""));
        assertEquals("\"a\",\"b\"\"\"",DmSqlNames.columns(List.of("a","b\"")));
        assertThrows(IllegalArgumentException.class,()->DmSqlNames.quote(null));
        assertThrows(IllegalArgumentException.class,()->DmSqlNames.quote(""));
        assertThrows(IllegalArgumentException.class,()->DmSqlNames.quote("bad\0name"));
    }
    @ParameterizedTest @ValueSource(ints={0,3,6})
    void timeRendersItsKnownScaleWithoutBecomingTimestamp(int scale) {
        String sql=render(Types.TIME,ColumnMetaData.TYPE_TIME,scale);
        assertTrue(sql.contains("TIME("+scale+")"));assertFalse(sql.contains("TIMESTAMP"));
    }
    @ParameterizedTest @ValueSource(ints={-1,7,9})
    void timeUnknownOrUnsupportedScaleIsRejected(int scale) {
        assertThrows(UnsupportedOperationException.class,()->render(Types.TIME,ColumnMetaData.TYPE_TIME,scale));
    }
    @Test void timezoneTimeAndTimestampCannotSilentlyLoseZone() {
        assertThrows(UnsupportedOperationException.class,()->render(Types.TIME_WITH_TIMEZONE,ColumnMetaData.TYPE_TIME,6));
        assertThrows(UnsupportedOperationException.class,()->render(Types.TIMESTAMP_WITH_TIMEZONE,ColumnMetaData.TYPE_TIMESTAMP,6));
    }
    private static String render(int jdbcType,int valueType,int scale) {
        var description=new ColumnDescription();description.setFieldName("at");description.setDbType(ProductTypeEnum.DM);
        description.setFieldType(jdbcType);description.setValType(valueType);description.setLength(scale);description.setPrecision(0);
        return new DatabaseDmImpl().getFieldDefinition(description.getMetaData(),List.of(),false,false,false);
    }
}
