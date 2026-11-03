package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataPushIdentifierQuotesTest {

    @Test
    @DisplayName("按产品类型加引号，保留字可用")
    void quoteReservedWord() {
        assertEquals("\"order\"", DataPushIdentifierQuotes.quoteColumn(ProductTypeEnum.POSTGRESQL, "order"));
        assertEquals("`order`", DataPushIdentifierQuotes.quoteColumn(ProductTypeEnum.MYSQL, "order"));
    }

    @Test
    @DisplayName("模式名与表名按产品类型加引号")
    void quoteSchemaAndTable() {
        assertEquals("\"public\"", DataPushIdentifierQuotes.quoteSchema(ProductTypeEnum.POSTGRESQL, "public"));
        assertEquals("\"order\"", DataPushIdentifierQuotes.quoteTable(ProductTypeEnum.POSTGRESQL, "order"));
        assertEquals("`order`", DataPushIdentifierQuotes.quoteTable(ProductTypeEnum.MYSQL, "order"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushIdentifierQuotes.quoteTable(ProductTypeEnum.POSTGRESQL, "t;drop"));
    }

    @Test
    @DisplayName("拒绝可注入的字段名")
    void rejectUnsafe() {
        assertThrows(IllegalArgumentException.class,
                () -> DataPushIdentifierQuotes.quoteColumn(ProductTypeEnum.POSTGRESQL, "id;drop"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushIdentifierQuotes.quoteColumn(ProductTypeEnum.POSTGRESQL, "id--"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushIdentifierQuotes.quoteColumn(ProductTypeEnum.POSTGRESQL, ""));
    }

}
