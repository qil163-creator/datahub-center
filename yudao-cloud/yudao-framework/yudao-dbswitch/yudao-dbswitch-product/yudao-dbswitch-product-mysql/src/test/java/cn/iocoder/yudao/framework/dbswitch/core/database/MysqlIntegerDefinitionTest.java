package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlIntegerDefinitionTest {
    @ParameterizedTest
    @CsvSource({"9, INT", "10, BIGINT", "11, BIGINT", "18, BIGINT", "19, DECIMAL(19)"})
    void numericPrecisionUsesCapacityRatherThanIntegerDisplayWidth(int digits, String sqlType) {
        assertEquals("`amount`  " + sqlType, definition(Types.NUMERIC, digits).trim());
    }

    @Test
    void explicitDecimalRetainsItsDeclaration() {
        assertEquals("`amount`  DECIMAL(10, 0)", definition(Types.DECIMAL, 10).trim());
    }

    private static String definition(int sourceType, int digits) {
        var column = new ColumnDescription();
        column.setDbType(ProductTypeEnum.POSTGRESQL);
        column.setFieldName("amount");
        column.setFieldType(sourceType);
        column.setPrecisionSize(digits);
        column.setScaleSize(0);
        column.setNullable(true);
        return new DatabaseMysqlImpl().getFieldDefinition(new ColumnMetaData(column), List.of(), false, false, false);
    }
}
