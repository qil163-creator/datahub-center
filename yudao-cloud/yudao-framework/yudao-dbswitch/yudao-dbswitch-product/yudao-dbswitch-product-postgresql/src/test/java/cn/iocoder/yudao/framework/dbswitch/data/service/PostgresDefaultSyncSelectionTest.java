package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.DBTableType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables.CatalogRow;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables.Listed;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目录行是伪造的，不打开数据库连接。 */
class PostgresDefaultSyncSelectionTest {
    @Test void unnamedPostgresqlSyncDropsPartitionTablesAndNamedTablesStay() throws Exception {
        var classified = PostgresPartitionTables.classify(rows());
        List<TableDescription> listed = List.copyOf(classified);
        var leaf = one(classified, "leaf");
        var child = one(classified, "child");
        assertEquals("TABLE", leaf.getTableType());
        assertEquals("TABLE", child.getTableType());
        assertFalse(leaf.defaultSync());
        assertTrue(child.defaultSync());
        var dialect = new DatabasePostgresImpl();
        Function<List<TableDescription>, List<TableDescription>> sync = dialect::defaultSyncTargets;

        assertEquals(List.of("orders", "orders_view", "orders_mat", "base", "child", "shared", "foreign_data"),
                names(MigrationService.selectSourceTables(ProductTypeEnum.POSTGRESQL, null, List.of(), List.of(), listed, sync)));
        assertEquals(List.of("orders", "base", "child", "shared", "foreign_data"),
                names(MigrationService.selectSourceTables(ProductTypeEnum.POSTGRESQL, DBTableType.TABLE, List.of(), List.of(), listed, sync)));
        assertEquals(List.of("orders_view", "orders_mat"),
                names(MigrationService.selectSourceTables(ProductTypeEnum.POSTGRESQL, DBTableType.VIEW, List.of(), List.of(), listed, sync)));
        assertEquals(List.of("orders", "orders_view", "orders_mat", "base", "shared", "foreign_data"),
                names(MigrationService.selectSourceTables(ProductTypeEnum.POSTGRESQL, null, List.of(), List.of("child"), listed, sync)));

        Function<List<TableDescription>, List<TableDescription>> unused = ignored -> {
            throw new AssertionError("named or non-PostgreSQL selection must not call defaultSyncTargets");
        };
        assertEquals(List.of("orders_view", "root", "leaf"), names(MigrationService.selectSourceTables(
                ProductTypeEnum.POSTGRESQL, DBTableType.TABLE, List.of("leaf", "root", "orders_view"), List.of(), listed, unused)));
        assertEquals(List.of("orders", "orders_view", "orders_mat", "root", "leaf", "base", "child", "shared", "foreign_data"),
                names(MigrationService.selectSourceTables(ProductTypeEnum.MYSQL, null, List.of(), List.of(), listed, unused)));
    }

    private static Listed one(List<Listed> listed, String name) {
        return listed.stream().filter(table -> name.equals(table.getTableName())).findFirst().orElseThrow();
    }

    private static List<String> names(List<TableDescription> tables) {
        return tables.stream().map(TableDescription::getTableName).toList();
    }

    private static List<CatalogRow> rows() {
        var rows = new ArrayList<CatalogRow>();
        rows.add(new CatalogRow("public", "orders", "sales", "r", false, null, null));
        rows.add(new CatalogRow("public", "orders_view", null, "v", false, null, null));
        rows.add(new CatalogRow("public", "orders_mat", null, "m", false, null, null));
        rows.add(new CatalogRow("public", "root", null, "p", false, null, null));
        rows.add(new CatalogRow("public", "leaf", null, "r", true, "public", "root"));
        rows.add(new CatalogRow("public", "base", null, "r", false, null, null));
        rows.add(new CatalogRow("public", "child", null, "r", false, "public", "base"));
        rows.add(new CatalogRow("public", "shared", null, "r", false, "public", "base"));
        rows.add(new CatalogRow("public", "shared", null, "r", false, "public", "second_parent"));
        rows.add(new CatalogRow("public", "foreign_data", null, "f", false, null, null));
        return rows;
    }
}
