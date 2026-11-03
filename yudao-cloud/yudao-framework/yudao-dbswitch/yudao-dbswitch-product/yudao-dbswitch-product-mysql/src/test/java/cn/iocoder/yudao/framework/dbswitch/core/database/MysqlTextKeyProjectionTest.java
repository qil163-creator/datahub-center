package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlTextKeyProjectionTest {
    private final DatabaseMysqlImpl database = mock(DatabaseMysqlImpl.class, CALLS_REAL_METHODS);

    @Test
    void quotesIdentifiersAndKeepsTextAsHexOutsideSqlGrammar() {
        var projection = database.textKeyProjection("a`b", "c`d", 256);
        assertEquals("CAST(CONVERT(`a``b`.`c``d` USING utf8mb4) AS BINARY)", projection.identitySql());
        assertEquals("NULLIF(`a``b`.`c``d`, `a``b`.`c``d`)", projection.typeSql());
        String value = "a\\' OR 1=1 -- 中国😀 ";
        assertEquals(projection.identitySql() + " = X'" + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))
                + "'", projection.equalitySql(value));
        assertTrue(projection.groupSql().contains("CHAR(512) CHARACTER SET ascii"));
        assertTrue(projection.groupSql().endsWith("COLLATE ascii_bin"));
    }

    @Test
    void invalidBoundsIdentifiersAndMalformedOrOversizedValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> database.textKeyProjection(null, "id", 0));
        assertThrows(IllegalArgumentException.class, () -> database.textKeyProjection(null, "id", 4097));
        var projection = database.textKeyProjection(null, "id", 256);
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("a".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql(null));
        assertThrows(IllegalArgumentException.class, () -> database.textKeyProjection(null, "a\0b", 256));
    }
}
