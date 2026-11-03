package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationHookAnalyzePlanTest {
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "SQLITE3"})
    void onlyOneSchemaQualifiedStatisticsTargetIsAdmittedWithoutChangingTheExternalParser(ProductTypeEnum product) {
        for (String sql : List.of("ANALYZE public.fixture", "analyze \"main\".\"fixture;table\"",
                "/* statistics */ ANALYZE \"schema\".\"a\"\"b\" -- trailing comment")) {
            var plan = SqlScriptPlan.parseMigrationHook(product, "SELECT 1;" + sql + "\n;");
            assertEquals(2, plan.steps().size());
            assertEquals(sql, plan.steps().getLast().sql());
            assertEquals(SqlStatementClassifier.Kind.OTHER, plan.steps().getLast().kind());
            assertEquals(0, plan.steps().getLast().parameterCount());
            assertThrows(RuntimeException.class, () -> SqlScriptPlan.parse(product, sql));
            assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(product, sql));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "SQLITE3"})
    void globalSchemaOnlyOptionsMultipleTargetsAndOtherMaintenanceCannotReachTheFirstStep(ProductTypeEnum product) {
        for (String sql : List.of("ANALYZE", "ANALYZE main", "ANALYZE fixture", "ANALYZE VERBOSE main.fixture",
                "ANALYZE (SKIP_LOCKED true) main.fixture", "ANALYZE (VERBOSE false) main.fixture",
                "ANALYZE main.fixture(id)", "ANALYZE main.fixture,main.other", "ANALYZE db.main.fixture",
                "ANALYZE main.*", "ANALYZE 'main'.'fixture'", "ANALYZE main.`fixture`",
                "ANALYZE main.fixture WHERE id=1", "ANALYZE main.fixture COMMIT",
                "VACUUM", "VACUUM main.fixture", "VACUUM ANALYZE main.fixture", "REINDEX main.fixture",
                "PRAGMA optimize", "ANALYZE main.fixture; COMMIT")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SqlScriptPlan.parseMigrationHook(product, "SELECT 1;" + sql), sql);
            assertNull(failure.getCause());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "SQLITE3", "UNKNOWN", "MONGODB",
            "ELASTICSEARCH", "INFLUXDB3"}, mode = EnumSource.Mode.EXCLUDE)
    void statisticsAdmissionIsNotInferredForOtherProducts(ProductTypeEnum product) {
        assertThrows(IllegalArgumentException.class,
                () -> SqlScriptPlan.parseMigrationHook(product, "ANALYZE main.fixture"));
    }
}
