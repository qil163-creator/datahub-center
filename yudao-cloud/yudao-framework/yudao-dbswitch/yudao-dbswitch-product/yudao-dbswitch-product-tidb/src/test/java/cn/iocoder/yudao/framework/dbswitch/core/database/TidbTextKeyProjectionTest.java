package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TidbTextKeyProjectionTest {
    @Test void witnessHasNoPayloadAndKeysRemainOutsideSqlGrammar() {
        var database = mock(DatabaseTidbImpl.class, CALLS_REAL_METHODS);
        var projection = database.textKeyProjection("a`b", "c`d", 256);
        assertEquals("(SELECT `a``b`.`c``d` WHERE FALSE)", projection.typeSql());
        assertTrue(projection.equalitySql("a\\' OR 1=1 -- 中国😀").matches(".* = X'[0-9a-f]+'"));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> projection.equalitySql("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> database.textKeyProjection(null, "id", 0));
        assertThrows(IllegalArgumentException.class, () -> database.textKeyProjection(null, "id", 4097));
        assertThrows(IllegalArgumentException.class, () -> database.textKeyProjection(null, "a\0b", 256));
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"TIDB", "MYSQL"}, mode = EnumSource.Mode.EXCLUDE)
    void inheritanceDoesNotOpenOtherProducts(ProductTypeEnum product) {
        var database = mock(DatabaseTidbImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(product);
        assertThrows(UnsupportedOperationException.class, () -> database.textKeyProjection("k", "id", 256));
    }
}
