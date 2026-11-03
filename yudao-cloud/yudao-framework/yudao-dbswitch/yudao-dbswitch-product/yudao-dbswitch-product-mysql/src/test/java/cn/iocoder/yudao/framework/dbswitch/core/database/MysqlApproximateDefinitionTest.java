package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlApproximateDefinitionTest {
    @ParameterizedTest
    @CsvSource({"8, DOUBLE", "6, DOUBLE", "7, FLOAT"})
    void approximateJdbcTypesDoNotBecomeDecimal(int jdbcType, String expected) {
        ColumnDescription column = new ColumnDescription(); column.setFieldName("value"); column.setFieldType(jdbcType);
        column.setDbType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL);
        column.setPrecisionSize(17); column.setScaleSize(0); column.setNullable(true);
        assertEquals("`value`  " + expected, new DatabaseMysqlImpl().getFieldDefinition(new ColumnMetaData(column),
                List.of(), false, false, false).trim());
    }
}

