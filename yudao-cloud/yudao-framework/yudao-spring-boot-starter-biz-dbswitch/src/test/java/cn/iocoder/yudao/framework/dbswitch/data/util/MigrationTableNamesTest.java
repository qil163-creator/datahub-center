package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTableNamesTest {
    @Test
    void hiveOverridesUppercaseAndAppliesPrefixOnce() {
        var source = new SourceDataSourceProperties(); source.setSourceType(1);
        var target = new TargetDataSourceProperties(); target.setTablePrefix("ods_");
        target.setTargetProductType(ProductTypeEnum.HIVE); target.setUppercase(true);
        assertEquals("ods_customer", MigrationTableNames.resolve(source, target, "CUSTOMER"));
        assertEquals("ods_customer", MigrationTableNames.resolve(source, target, "ods_CUSTOMER"));
        assertTrue(target.getUppercase());
    }
    @Test
    void sqlSourceUsesExplicitTargetWithoutTablePrefix() {
        var source = new SourceDataSourceProperties(); source.setSourceType(2);
        var target = new TargetDataSourceProperties(); target.setTargetTable("result"); target.setTablePrefix("ods_");
        assertEquals("result", MigrationTableNames.resolve(source, target, "SELECT 1"));
    }

    @ParameterizedTest
    @CsvSource({"NONE,ods_OrderItem", "UPPER,ODS_ORDERITEM", "LOWER,ods_orderitem",
            "SNAKE,ods_order_item", "CAMEL,odsOrderitem"})
    void explicitModeRunsAfterMappingAndPrefixIndependentlyOfLegacyAndColumnModes(CaseConvertEnum mode, String expected) {
        var source = new SourceDataSourceProperties(); source.setSourceType(1);
        source.setRegexTableMapper(List.of(new PatternMapper("^src_", "")));
        var target = new TargetDataSourceProperties(); target.setTablePrefix("ods_");
        target.setTableNameCase(mode); target.setColumnNameCase(CaseConvertEnum.UPPER); target.setLowercase(true);
        assertEquals(expected, MigrationTableNames.resolve(source, target, "src_OrderItem"));
        assertEquals(mode, target.getTableNameCase()); assertTrue(target.getLowercase());
    }

    @ParameterizedTest @EnumSource(CaseConvertEnum.class)
    void hiveKeepsLowercaseEvenWithAnExplicitMode(CaseConvertEnum mode) {
        var source = new SourceDataSourceProperties(); source.setSourceType(1);
        var target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.HIVE);
        target.setTableNameCase(mode); target.setUppercase(true);
        assertEquals("order_item", MigrationTableNames.resolve(source, target, "Order_Item"));
    }

    @Test void absentTableModeKeepsLegacyBehaviorWithoutInheritingTheColumnMode() {
        var source = new SourceDataSourceProperties(); source.setSourceType(1);
        var target = new TargetDataSourceProperties(); target.setColumnNameCase(CaseConvertEnum.SNAKE);
        assertNull(target.getTableNameCase());
        assertEquals("OrderItem", MigrationTableNames.resolve(source, target, "OrderItem"));
        target.setLowercase(true);
        assertEquals("orderitem", MigrationTableNames.resolve(source, target, "OrderItem"));
        target.setUppercase(true);
        assertEquals("ORDERITEM", MigrationTableNames.resolve(source, target, "OrderItem"));
    }

    @Test void sqlTargetUsesItsOwnModeAndEmptyConvertedNamesAreRejected() {
        var source = new SourceDataSourceProperties(); source.setSourceType(2);
        var target = new TargetDataSourceProperties(); target.setTargetTable("OrderItem"); target.setTablePrefix("ods_");
        target.setTableNameCase(CaseConvertEnum.SNAKE);
        assertEquals("order_item", MigrationTableNames.resolve(source, target, "SELECT 1"));
        target.setTargetTable("__"); target.setTableNameCase(CaseConvertEnum.CAMEL);
        assertThrows(IllegalArgumentException.class, () -> MigrationTableNames.resolve(source, target, "SELECT 1"));
        source.setSourceType(1); target.setTablePrefix(null);
        assertThrows(IllegalArgumentException.class, () -> MigrationTableNames.resolve(source, target, "__"));
    }
}
