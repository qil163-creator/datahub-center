package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresCopyIdentifierTest {
    @Test void physicalQuotesAreAlwaysEscapedRatherThanTreatedAsSqlFragments() {
        assertEquals("\"a\"\"b\"",PostgreSqlUtils.quoteIdentifier("a\"b"));
        assertEquals("\"\"\"literal\"\"\"",PostgreSqlUtils.quoteIdentifier("\"literal\""));
        assertEquals("\"SELECT; 中文\"",PostgreSqlUtils.quoteIdentifier("SELECT; 中文"));
        assertEquals("\"s\"\"x\".\"t\"\"x\"",PostgreSqlUtils.getFullyQualifiedTableName("s\"x","t\"x",true));
    }
    @Test void emptyControlAndOverlengthNamesFailBeforeCopy() {
        assertThrows(IllegalArgumentException.class,()->PostgreSqlUtils.quoteIdentifier(null));
        for(String name:new String[]{"","x\0y","x\ny","x\ry","x\ty","x\u007fy","x".repeat(64),"中".repeat(22)})
            assertThrows(IllegalArgumentException.class,()->PostgreSqlUtils.quoteIdentifier(name));
        assertEquals('"'+"中".repeat(21)+'"',PostgreSqlUtils.quoteIdentifier("中".repeat(21)));
    }
}
