package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedInsertOnlyPlanTest {
    private static final ProductTypeEnum MYSQL = ProductTypeEnum.MYSQL;

    @Test void explicitInsertKeepsEachStatementsCompleteParameterOrderAndOldEntryRemainsClosed() {
        List<String> statements = List.of("INSERT INTO items(id,value) VALUES (?,?),(?,?)", "INSERT INTO items(id) VALUES (?)");
        var plan = ManagedSchemaScriptPlan.parseInsertOnly(MYSQL, "demo", statements);
        assertEquals(2, plan.steps().size());
        assertEquals(List.of(0, 1, 2, 3), plan.steps().getFirst().parameterOrder());
        assertEquals(List.of(0), plan.steps().get(1).parameterOrder());
        assertEquals("DML", plan.steps().getFirst().operation());
        assertTrue(plan.steps().getFirst().sql().contains("`demo`.`items`"));
        assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parse(MYSQL, "demo", statements));
        assertThrows(UnsupportedOperationException.class,
                () -> ManagedSchemaScriptPlan.parseInsertOnly(ProductTypeEnum.POSTGRESQL, "demo", statements));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE items SET id=?", "DELETE FROM items", "SELECT * FROM items", "CREATE TABLE items(id INT)",
            "INSERT INTO items(id) SELECT id FROM source", "INSERT IGNORE INTO items(id) VALUES (?)",
            "REPLACE INTO items(id) VALUES (?)", "INSERT INTO items(id) VALUES (?) ON DUPLICATE KEY UPDATE id=?",
            "INSERT INTO outside.items(id) VALUES (?)", "INSERT INTO items(id) VALUES (?); DELETE FROM items",
            "INSERT INTO items(id) VALUES ((SELECT id FROM source))", "INSERT INTO items VALUES (?)",
            "INSERT INTO items(id) VALUES (1)", "INSERT INTO items(id,ID) VALUES (?,?)",
            "INSERT INTO items(id,value) VALUES (?)", "INSERT INTO items(id) VALUES (@session_value)",
            "INSERT LOW_PRIORITY INTO items(id) VALUES (?)", "INSERT DELAYED INTO items(id) VALUES (?)",
            "INSERT INTO items PARTITION(p0) (id) VALUES (?)"
    })
    void rejectsEveryWiderMutationReadAndUnboundValue(String sql) {
        assertThrows(IllegalArgumentException.class,
                () -> ManagedSchemaScriptPlan.parseInsertOnly(MYSQL, "demo", List.of(sql)));
    }

    @Test void rejectsMixedListsBeforeReturningAnyPartialGrant() {
        assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parseInsertOnly(MYSQL, "demo",
                List.of("INSERT INTO items(id) VALUES (?)", "CREATE TABLE other(id INT)")));
    }

    @Test void acceptsOnlyFixedMysqlCreateOptionsAndRequiredNativeTypes() {
        var plan = ManagedSchemaScriptPlan.parse(MYSQL, "demo", List.of("CREATE TABLE items(value MEDIUMTEXT, large_value LONGTEXT, at_time DATETIME(6)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"));
        assertEquals("DDL", plan.steps().getFirst().operation());
        for (String suffix : List.of("ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin",
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin DATA DIRECTORY='/tmp/escape'",
                "ENGINE=InnoDB", "ENGINE=FEDERATED CONNECTION='external'")) {
            assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parse(MYSQL, "demo",
                    List.of("CREATE TABLE items(id INT) " + suffix)));
        }
    }
}
