package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlTypeSemanticsTest {

    @Test
    void declaredDecimal_shouldKeepTotalPrecisionAndZeroScale() {
        for (ProductTypeEnum product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL)) {
            for (int scale : List.of(0, 2, 18)) {
                ColumnDescription column = new ColumnDescription();
                column.setFieldName("amount");
                column.setNullable(true);
                SqlTypeSemantics.applyDeclaredType(column, "decimal", null, 18, scale);
                for (List<String> keys : List.of(List.<String>of(), List.of("amount"))) {
                    String ddl = GenerateSqlUtils.getDDLCreateTableSQL(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(product), List.of(column),
                            keys, "public", "decimal_contract", false).replaceAll("\\s+", "");
                    assertTrue(ddl.contains("(18," + scale + ")"), ddl);
                    assertFalse(ddl.contains("(20,2)"), ddl);
                }
            }
        }
    }

    @Test
    void metadata_shouldPreserveNativeTypeAndRejectUnknownFallback() {
        ColumnDescription column = new ColumnDescription();
        column.setFieldType(Types.OTHER);
        column.setFieldTypeName("jsonb");
        assertEquals("jsonb", SqlTypeSemantics.metadataTypeName(column));
        assertNotEquals(SqlTypeSemantics.canonicalName("timestamp(6)"),
                SqlTypeSemantics.canonicalName("timestamp(6) with time zone"));
        assertNotEquals(SqlTypeSemantics.canonicalName("int(11)"),
                SqlTypeSemantics.canonicalName("int(11) unsigned"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlTypeSemantics.applyDeclaredType(column, "geometry", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> SqlTypeSemantics.applyDeclaredType(column, "decimal", 18, 2, null));
    }
}
