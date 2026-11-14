package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaSynchronization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 检查原生规划语义与注入边界；这些测试不替代 Doris 服务端验收。 */
class DorisSchemaSynchronizationTest {
    private final DatabaseDorisImpl database = new DatabaseDorisImpl();

    @Test void createUsesNativeUniqueKeyAndPreservesCommentsDefaultsAndExplicitLayout() {
        var id = column("id", "bigint", null, true);
        var note = column("note", "varchar", 20, false);
        note.setDefaultValue("O'Reilly");
        note.setRemarks("字段'说明");
        var layout = new DorisCreateTableOptions(null, "HASH", List.of("id"), "FIXED", 3, 1);
        var plan = SchemaSynchronization.createPlan(database, "dw", "orders", "订单'说明", List.of(id, note),
                Map.of(DorisCreateTableOptions.TABLE_PROPERTY, layout.toJson()));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        var table = DorisNativeSchema.parse(plan.statements().getFirst());
        assertTrue(table.unique());
        assertEquals(List.of("id"), table.keys());
        assertEquals(3, table.buckets());
        assertEquals("订单'说明", table.comment());
        assertEquals("字段'说明", table.column("note").comment());
        assertEquals("'O\\'Reilly'", table.column("note").defaultSql());
    }

    @Test void createWithoutPrimaryKeyUsesDuplicateModelAndRejectsDuplicateNamesOrUnsafeDefaults() {
        var id = column("id", "bigint", null, false);
        var plan = SchemaSynchronization.createPlan(database, "dw", "events", "", List.of(id));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertEquals("DUPLICATE", DorisNativeSchema.parse(plan.statements().getFirst()).model());
        assertFalse(SchemaSynchronization.createPlan(database, "dw", "events", "", List.of(id, id)).ready());
        id.setDefaultValue("0); DROP TABLE secret;--");
        var rejected = SchemaSynchronization.createPlan(database, "dw", "events", "", List.of(id));
        assertFalse(rejected.ready());
        assertTrue(rejected.statements().isEmpty());
    }

    @Test void unchangedTableProducesNoDdlAndCommentsHaveNativeSyntax() {
        var fields = List.of(column("id", "bigint", null, true), column("note", "varchar", 20, false));
        var actual = created(fields);
        assertTrue(observed(fields, actual).statements().isEmpty());
        fields.getLast().setRemarks("new comment");
        var plan = observed(fields, actual);
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertEquals(List.of("ALTER TABLE `dw`.`orders` MODIFY COLUMN `note` COMMENT 'new comment'"), plan.statements());
    }

    @Test void ordinaryChangesAreOneSchemaJobAndPreserveExistingLayout() {
        var fields = List.of(column("id", "bigint", null, true), column("note", "varchar", 20, false),
                column("old", "int", null, false));
        var desired = List.of(column("id", "bigint", null, true), column("note", "varchar", 50, false),
                column("new", "int", null, false));
        var plan = observed(desired, created(fields));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertEquals(1, plan.statements().size());
        String sql = plan.statements().getFirst();
        assertTrue(sql.contains("MODIFY COLUMN `note`"));
        assertTrue(sql.contains("ADD COLUMN `new`"));
        assertTrue(sql.contains("DROP COLUMN `old`"));
        assertFalse(sql.contains("DISTRIBUTED"));
        assertFalse(sql.contains("DROP TABLE"));
    }

    @Test void keyAndDependentColumnsCannotBeSilentlyChangedOrDropped() {
        var fields = List.of(column("id", "bigint", null, true), column("note", "varchar", 20, false));
        var actual = created(fields);
        var renamed = List.of(column("new_id", "bigint", null, true), fields.getLast());
        var keyPlan = observed(renamed, actual);
        assertFalse(keyPlan.ready());
        assertTrue(keyPlan.statements().isEmpty());
        var changed = List.of(column("id", "varchar", 20, true), fields.getLast());
        assertFalse(observed(changed, actual).ready());
    }

    @Test void escapedNamesAndCommentsRemainSingleDdlStatements() {
        var fields = List.of(column("id", "bigint", null, true), column("odd`name", "varchar", 20, false));
        fields.getLast().setRemarks("comment'; DROP TABLE other;--");
        var plan = SchemaSynchronization.createPlan(database, "dw", "orders", "", fields);
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertEquals("comment'; DROP TABLE other;--", DorisNativeSchema.parse(plan.statements().getFirst())
                .column("odd`name").comment());
    }

    @Test void decimalAndTimestampPrecisionSurvivePortableModelMapping() {
        var decimal = new ColumnDescription();
        decimal.setFieldName("amount");
        SqlTypeSemantics.applyDeclaredType(decimal, "decimal", null, 38, 18);
        decimal.setNullable(true);
        var timestamp = column("created_at", "timestamp", 6, false);
        var actual = created(List.of(column("id", "bigint", null, true), decimal, timestamp));
        assertEquals("decimal(38,18)", actual.column("amount").type());
        assertEquals("datetime(6)", actual.column("created_at").type());
    }

    @Test void frozenNativeScriptsAreBoundToExactlyOneTableAndRejectExtraOperations() {
        var fields = List.of(column("id", "bigint", null, true), column("note", "varchar", 20, false));
        String create = created(fields).ddl("dw", "orders");
        assertEquals(1, database.planModelingSchemaScript("dw", "orders", List.of(create)).steps().size());
        assertThrows(IllegalArgumentException.class, () -> database.planModelingSchemaScript("dw", "other", List.of(create)));
        for (String sql : List.of("ALTER TABLE `dw`.`orders` DROP COLUMN `note`; DELETE FROM `dw`.`secret`",
                "DROP TABLE `dw`.`orders`", "ALTER TABLE `dw`.`orders` ADD COLUMN bad INT DEFAULT (SELECT 1)",
                "ALTER TABLE `dw`.`orders` MODIFY COMMENT 'x'; SET GLOBAL x=1", "ALTER TABLE `other`.`orders` DROP COLUMN `note`")) {
            assertThrows(IllegalArgumentException.class, () -> database.planModelingSchemaScript("dw", "orders", List.of(sql)));
        }
    }

    @Test void frozenAlterScriptsPreserveEscapedNamesCommentsAndOneSchemaJob() {
        var before = List.of(column("id", "bigint", null, true), column("note", "varchar", 20, false));
        var after = List.of(column("id", "bigint", null, true), column("note", "varchar", 50, false),
                column("new", "int", null, false));
        after.getLast().setRemarks("text'; DROP TABLE other;--");
        var generated = observed(after, created(before));
        assertTrue(generated.ready(), generated.diagnostics().toString());
        var frozen = database.planModelingSchemaScript("dw", "orders", generated.statements());
        assertEquals(1, frozen.steps().size());
        assertEquals(generated.statements(), frozen.steps().stream().map(step -> step.sql()).toList());
    }

    @Test void nativeMetadataKeepsUniqueKeysAndDoesNotInventUniquenessForDuplicateSortKeys() {
        var fields = List.of(column("id", "bigint", null, true), column("note", "varchar", 20, false));
        fields.getLast().setRemarks("中文说明");
        var source = new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(
                cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS, "Doris", "doris-4.0.8-rc02-bc8ea1bac6d",
                "DORIS_CATALOG_V1", java.time.Instant.now());
        var unique = DorisSchemaSynchronization.metadata(created(fields), source);
        assertEquals(List.of("id"), unique.primaryKeys());
        assertTrue(unique.columns().getFirst().isPk());
        assertEquals("中文说明", unique.columns().getLast().getRemarks());
        assertEquals("varchar(80)", unique.columns().getLast().getMetadataEvidence().nativeType().value());
        fields.getFirst().setPk(false);
        var duplicate = DorisSchemaSynchronization.metadata(created(fields), source);
        assertTrue(duplicate.primaryKeys().isEmpty());
        assertFalse(duplicate.columns().getFirst().isPk());
    }

    @Test void unsupportedDefaultChangesAreRejectedBeforeAnyDdlIsExposed() {
        var id = column("id", "bigint", null, true);
        var note = column("note", "varchar", 20, false);
        note.setDefaultValue("old");
        var actual = created(List.of(id, note));
        for (String changedDefault : java.util.Arrays.asList("new", null)) {
            var changed = column("note", "varchar", 50, false);
            changed.setDefaultValue(changedDefault);
            var plan = observed(List.of(id, column("added", "int", null, false), changed), actual);
            assertFalse(plan.ready());
            assertTrue(plan.statements().isEmpty());
            assertEquals("DORIS_DEFAULT_CHANGE_REQUIRES_REBUILD", plan.diagnostics().getFirst().code());
        }
        var withDefault = column("note", "varchar", 20, false);
        withDefault.setDefaultValue("new");
        assertFalse(observed(List.of(id, withDefault), created(List.of(id,
                column("note", "varchar", 20, false)))).ready());
    }

    @Test void nullabilityCanBeRelaxedButCannotBeTightenedByOrdinaryAlter() {
        var id = column("id", "bigint", null, true);
        var required = column("note", "varchar", 20, false);
        required.setNullable(false);
        var optional = column("note", "varchar", 20, false);
        var rejected = observed(List.of(id, required), created(List.of(id, optional)));
        assertFalse(rejected.ready());
        assertTrue(rejected.statements().isEmpty());
        assertEquals("DORIS_NULLABILITY_TIGHTENING_REQUIRES_REBUILD", rejected.diagnostics().getFirst().code());
        var relaxed = observed(List.of(id, optional), created(List.of(id, required)));
        assertTrue(relaxed.ready(), relaxed.diagnostics().toString());
        assertEquals(1, relaxed.statements().size());
        assertTrue(relaxed.statements().getFirst().contains("varchar(80) NULL"));
    }

    @Test void varcharShrinkingIsRejectedWhileWideningPreservesOriginalDefault() {
        var id = column("id", "bigint", null, true);
        var before = column("note", "varchar", 20, false);
        before.setDefaultValue("existing");
        var smaller = column("note", "varchar", 10, false);
        smaller.setDefaultValue("existing");
        var rejected = observed(List.of(id, smaller), created(List.of(id, before)));
        assertFalse(rejected.ready());
        assertTrue(rejected.statements().isEmpty());
        assertEquals("DORIS_STRING_NARROWING_REQUIRES_REBUILD", rejected.diagnostics().getFirst().code());
        var wider = column("note", "varchar", 40, false);
        wider.setDefaultValue("existing");
        var accepted = observed(List.of(id, wider), created(List.of(id, before)));
        assertTrue(accepted.ready(), accepted.diagnostics().toString());
        assertTrue(accepted.statements().getFirst().contains("DEFAULT 'existing'"));
    }

    @Test void caseOnlyColumnRenameCannotBecomeConflictingAddAndDrop() {
        var id = column("id", "bigint", null, true);
        var actual = created(List.of(id, column("Note", "varchar", 20, false)));
        var plan = observed(List.of(id, column("note", "varchar", 20, false)), actual);
        assertFalse(plan.ready());
        assertTrue(plan.statements().isEmpty());
        assertEquals("DORIS_COLUMN_RENAME_REQUIRES_EXPLICIT_PLAN", plan.diagnostics().getFirst().code());
    }

    @Test void stringCannotBeImplicitlyNarrowedToBoundedTextOrParsedIntoNumbers() {
        var id = column("id", "bigint", null, true);
        var actual = created(List.of(id, column("note", "text", null, false)));
        for (var target : List.of(column("note", "varchar", 20, false), column("note", "bigint", null, false))) {
            var plan = observed(List.of(id, target), actual);
            assertFalse(plan.ready());
            assertTrue(plan.statements().isEmpty());
            assertEquals("DORIS_TYPE_CHANGE_REQUIRES_EXPLICIT_MIGRATION", plan.diagnostics().getFirst().code());
        }
    }

    @Test void numericAndTemporalPrecisionLossRequiresAnExplicitMigration() {
        var id = column("id", "bigint", null, true);
        var cases = List.of(
                List.of(column("value", "bigint", null, false), column("value", "tinyint", null, false)),
                List.of(column("value", "bigint", null, false), column("value", "double", null, false)),
                List.of(column("value", "bigint", null, false), decimal("value", 24, 4)),
                List.of(decimal("value", 18, 6), decimal("value", 18, 2)),
                List.of(decimal("value", 18, 6), decimal("value", 18, 8)),
                List.of(column("value", "timestamp", 6, false), column("value", "timestamp", 0, false)),
                List.of(column("value", "timestamp", 6, false), column("value", "date", null, false)));
        for (var pair : cases) {
            var plan = observed(List.of(id, pair.getLast()), created(List.of(id, pair.getFirst())));
            assertFalse(plan.ready());
            assertTrue(plan.statements().isEmpty());
            assertEquals("DORIS_TYPE_CHANGE_REQUIRES_EXPLICIT_MIGRATION", plan.diagnostics().getFirst().code());
        }
    }

    @Test void losslessNumericTemporalAndTextWideningRemainAvailable() {
        var id = column("id", "bigint", null, true);
        var cases = List.of(
                List.of(column("value", "tinyint", null, false), column("value", "bigint", null, false)),
                List.of(column("value", "int", null, false), column("value", "double", null, false)),
                List.of(decimal("value", 12, 2), decimal("value", 18, 4)),
                List.of(column("value", "date", null, false), column("value", "timestamp", 6, false)),
                List.of(column("value", "timestamp", 3, false), column("value", "timestamp", 6, false)),
                List.of(column("value", "varchar", 20, false), column("value", "text", null, false)));
        for (var pair : cases) {
            var plan = observed(List.of(id, pair.getLast()), created(List.of(id, pair.getFirst())));
            assertTrue(plan.ready(), plan.diagnostics().toString());
            assertEquals(1, plan.statements().size());
        }
    }

    private static ColumnDescription decimal(String name, int precision, int scale) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        SqlTypeSemantics.applyDeclaredType(column, "decimal", null, precision, scale);
        return column;
    }

    private DorisNativeSchema.Table created(List<ColumnDescription> fields) {
        var plan = SchemaSynchronization.createPlan(database, "dw", "orders", "", fields);
        assertTrue(plan.ready(), plan.diagnostics().toString());
        return DorisNativeSchema.parse(plan.statements().getFirst());
    }

    private SchemaSynchronization.Plan observed(List<ColumnDescription> fields, DorisNativeSchema.Table actual) {
        return DorisSchemaSynchronization.observed(database, "dw", "orders", "", fields, actual);
    }

    private static ColumnDescription column(String name, String type, Integer length, boolean primary) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        SqlTypeSemantics.applyDeclaredType(column, type, length, null, null);
        column.setNullable(!primary);
        column.setPk(primary);
        return column;
    }
}
