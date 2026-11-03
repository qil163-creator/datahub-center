package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MysqlTemporalDefinitionTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void preservesSupportedTimestampFractionsThroughColumnModel(int scale) {
        assertEquals("`seen`  DATETIME" + (scale == 0 ? "" : "(" + scale + ")"), definition(scale).trim());
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 8, 9})
    void refusesSilentLossOfSubMicrosecondPrecision(int scale) {
        assertThrows(UnsupportedOperationException.class, () -> definition(scale));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 6})
    void defaultTimestampUsesTheSameFractionalPrecision(int scale) {
        var column = new ColumnDescription();
        column.setFieldName("seen"); column.setFieldType(Types.TIMESTAMP);
        column.setScaleSize(scale); column.setNullable(true); column.setDefaultValue("CURRENT_TIMESTAMP");
        String fraction = scale == 0 ? "" : "(" + scale + ")";
        assertEquals("`seen`  DATETIME" + fraction + " DEFAULT CURRENT_TIMESTAMP" + fraction,
                new DatabaseMysqlImpl().getFieldDefinition(new ColumnMetaData(column), List.of(), false, false, false).trim());
    }

    private static String definition(int scale) {
        var column = new ColumnDescription();
        column.setFieldName("seen"); column.setFieldType(Types.TIMESTAMP); column.setFieldTypeName("TIMESTAMP");
        column.setScaleSize(scale); column.setNullable(true);
        return new DatabaseMysqlImpl().getFieldDefinition(new ColumnMetaData(column), List.of(), false, false, false);
    }
}
