package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOscarImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OscarDecimalDefinitionTest {
    private final DatabaseOscarImpl database = new DatabaseOscarImpl();

    @Test void exactDecimalsRetainTotalDigitsAndScaleIncludingPrimaryKeys() {
        assertEquals(" \"AMOUNT\"    NUMERIC(24,6)", definition(24, 6, false));
        assertEquals(" \"AMOUNT\"    NUMERIC(38,0)", definition(38, 0, true));
        assertEquals(" \"AMOUNT\"    NUMERIC(10,2)", definition(10, 2, true));
        assertEquals(" \"AMOUNT\"    NUMERIC(9,0)", definition(9, 0, false));
        assertEquals(" \"AMOUNT\"    NUMBER", definition(1000, 38, false));
    }

    @Test void unknownOrOutOfDomainDecimalsAreNotReplacedWithAnInteger() {
        for (int[] domain : List.of(new int[]{0, 0}, new int[]{1001, 0},
                new int[]{40, 39}, new int[]{2, 3}, new int[]{24, -1})) {
            assertThrows(UnsupportedOperationException.class, () -> definition(domain[0], domain[1], false));
        }
    }

    private String definition(int precision, int scale, boolean primaryKey) {
        var column = new ColumnDescription(); column.setDbType(ProductTypeEnum.OSCAR);
        column.setFieldName("AMOUNT"); column.setFieldType(Types.NUMERIC); column.setSigned(true);
        column.setPrecisionSize(precision); column.setScaleSize(scale);
        return database.getFieldDefinition(column.getMetaData(), primaryKey ? List.of("AMOUNT") : List.of(),
                false, false, false);
    }
}
