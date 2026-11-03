package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaSynchronizationTest {
    @Test void completeOptInPlansDropModifyPkAndCommentsWhileConservativeApiStillRejectsDrop() {
        var old = nativeColumn("old", "character varying(30)", MetadataValue.absent());
        var actual = MetadataSchemaPlanTest.snapshot(old);
        var desired = column("new", "varchar", 20, null, null, true, false, "'a''b; literal'", "quoted ' comment");
        var plan = SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "table comment",
                List.of(desired), actual, facts(null));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("ADD COLUMN \"new\"")));
        assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("DROP COLUMN \"old\"")));
        assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("COMMENT ON COLUMN")));
        assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("COMMENT ON TABLE")));
        assertFalse(new SchemaDiff().plan(new DatabasePostgresImpl(), "public", "target",
                MetadataSchemaPlanTest.snapshot(nativeColumn("new", "character varying(20)", MetadataValue.absent())), actual).ready());
        assertThrows(UnsupportedOperationException.class, () -> plan.statements().add("injected"));
    }

    @Test void primaryKeyReplacementUsesObservedConstraintBeforeDroppingItsColumn() {
        var old = nativeColumn("id", "bigint", MetadataValue.absent());
        old.setNullable(false);
        var actual = new TableSchemaMetadata(List.of(old), List.of("id"), MetadataSchemaPlanTest.SOURCE,
                MetadataValue.value(true), MetadataValue.absent());
        var plan = SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "",
                List.of(column("new_id", "bigint", null, null, null, true, true, null, "")), actual, facts("custom_pk"));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertEquals("ALTER TABLE \"public\".\"target\" DROP CONSTRAINT \"custom_pk\"", plan.statements().getFirst());
        assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("\"new_id\" BIGINT NOT NULL")));
        assertEquals("ALTER TABLE \"public\".\"target\" ADD PRIMARY KEY (\"new_id\")", plan.statements().getLast());
    }

    @Test void unsafeDefaultsAndDuplicateNamesExposeNoPartialSql() {
        var actual = MetadataSchemaPlanTest.snapshot(nativeColumn("existing", "text", MetadataValue.absent()));
        for (String input : List.of("0); DROP TABLE secrets;--", "now(); DELETE FROM secrets", "1/*comment*/", "pg_sleep(10)", "'unfinished", "E'a\\b'")) {
            assertThrows(IllegalArgumentException.class, () -> SchemaSynchronization.defaultExpression(ProductTypeEnum.POSTGRESQL, input));
            var desired = column("amount", "decimal", null, 20, 4, true, false, input, "");
            var plan = SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "", List.of(desired), actual, facts(null));
            assertFalse(plan.ready(), input);
            assertTrue(plan.statements().isEmpty(), input);
        }
        var duplicate = column("same", "varchar", 20, null, null, true, false, null, "");
        assertFalse(SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "",
                List.of(duplicate, duplicate), actual, facts(null)).ready());
    }

    @Test void typeConversionDropsOldDefaultBeforeAlterAndPreservesDecimalPrecision() {
        var actual = MetadataSchemaPlanTest.snapshot(nativeColumn("amount", "numeric(12,2)", MetadataValue.value("0")));
        var plan = SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "",
                List.of(column("amount", "decimal", null, 20, 4, false, false, "1.2500", "")), actual, facts(null));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertTrue(plan.statements().get(0).endsWith("DROP DEFAULT"));
        assertTrue(plan.statements().get(1).endsWith("TYPE NUMERIC(20, 4)"));
        assertTrue(plan.statements().get(2).endsWith("SET DEFAULT 1.2500"));
        assertTrue(plan.statements().stream().anyMatch(sql -> sql.endsWith("SET NOT NULL")));
    }

    @Test void nativeUnknownsAndIncomingReferencesFailClosed() {
        var actual = MetadataSchemaPlanTest.snapshot(nativeColumn("value", "text", MetadataValue.unknown()));
        var desired = column("value", "varchar", 20, null, null, true, false, null, "");
        assertFalse(SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "", List.of(desired), actual, facts(null)).ready());
        actual = MetadataSchemaPlanTest.snapshot(nativeColumn("value", "text", MetadataValue.absent()));
        assertFalse(SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "", List.of(desired), actual,
                new SchemaSynchronization.TableFacts(null, "", true, true)).ready());
        var unknownSequence = MetadataSchemaPlanTest.column("value", "text", MetadataValue.absent());
        var sequencePlan = SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "",
                List.of(desired), MetadataSchemaPlanTest.snapshot(unknownSequence), facts(null));
        assertFalse(sequencePlan.ready()); assertTrue(sequencePlan.statements().isEmpty());
        assertEquals("UNSUPPORTED_OR_UNKNOWN_COLUMN_SEMANTICS", sequencePlan.diagnostics().getFirst().code());
    }

    @Test void mysqlModifyIncludesNullableDefaultCommentAndPreservesCharacterSemantics() {
        var source = new MetadataProvenance(ProductTypeEnum.MYSQL, "MySQL", "8.4.10", "TEST_NATIVE_CATALOG", Instant.now());
        var old = new ColumnDescription();
        old.setFieldName("label"); old.setNullable(true);
        old.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value("varchar(30)"), MetadataValue.absent(),
                MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(30L),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value("utf8mb4"), MetadataValue.value("utf8mb4_bin")));
        var actual = new TableSchemaMetadata(List.of(old), List.of(), source, MetadataValue.value(true), MetadataValue.unknown());
        var desired = column("label", "varchar", 20, null, null, false, false, "'new'", "comment");
        var plan = SchemaSynchronization.planObserved(new DatabaseMysqlImpl(), "test", "target", "", List.of(desired), actual, facts(null));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertEquals(1, plan.statements().size());
        assertTrue(plan.statements().getFirst().contains("MODIFY COLUMN `label` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'new' COMMENT 'comment'"));
        assertFalse(SchemaSynchronization.planObserved(new DatabaseMysqlImpl(), "test", "target", "", List.of(desired), actual,
                new SchemaSynchronization.TableFacts(null, "", false, false)).ready());
    }

    @Test void quotingNamesAndCommentsCannotIntroduceAnotherStatement() {
        var actual = MetadataSchemaPlanTest.snapshot(nativeColumn("old", "text", MetadataValue.absent()));
        String unusual = "quoted\";literal";
        var desired = column(unusual, "varchar", 20, null, null, true, false, null, "' ; DROP TABLE secret;--");
        var plan = SchemaSynchronization.planObserved(new DatabasePostgresImpl(), "public", "target", "' ; literal",
                List.of(desired), actual, facts(null));
        assertTrue(plan.ready(), plan.diagnostics().toString());
        assertTrue(plan.statements().getFirst().contains("\"quoted\"\";literal\""));
        assertFalse(plan.toString().contains("secret"));
    }

    @Test void unverifiedCommentBackslashesAreRejectedWithoutPartialSql() {
        var actual = MetadataSchemaPlanTest.snapshot(nativeColumn("old", "text", MetadataValue.absent()));
        var desired = column("value", "varchar", 20, null, null, true, false, null, "backslash\\");
        for (var database : List.of(new DatabasePostgresImpl(), new DatabaseMysqlImpl())) {
            var plan = SchemaSynchronization.planObserved(database, "public", "target", "", List.of(desired), actual, facts(null));
            assertFalse(plan.ready()); assertTrue(plan.statements().isEmpty());
        }
    }

    @Test void typedDefaultsAndExplicitCreateShareOneSafeContract() {
        var pending = column("state", "varchar", 20, null, null, false, false, "pending", "state");
        var date = column("created", "date", null, null, null, false, false, "'2000-01-01'", "date");
        assertEquals("'pending'", SchemaSynchronization.defaultExpression(ProductTypeEnum.POSTGRESQL, pending));
        assertEquals("'2000-01-01'", SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL, date));
        assertEquals("'TRUE'", SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL,
                column("text", "varchar", 20, null, null, true, false, "TRUE", "")));
        assertEquals("'CURRENT_TIMESTAMP'", SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL,
                column("text", "varchar", 20, null, null, true, false, "CURRENT_TIMESTAMP", "")));
        assertEquals("'O''Reilly'", SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL,
                column("text", "varchar", 20, null, null, true, false, "O'Reilly", "")));
        assertThrows(IllegalArgumentException.class, () -> SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL,
                column("text", "varchar", 20, null, null, true, false, "'unfinished", "")));
        for (var database : List.of(new DatabasePostgresImpl(), new DatabaseMysqlImpl())) {
            var plan = SchemaSynchronization.createPlan(database, "test", "target", "comment", List.of(pending, date));
            assertTrue(plan.ready(), plan.diagnostics().toString());
            assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("DEFAULT 'pending'")));
            assertTrue(plan.statements().stream().anyMatch(sql -> sql.contains("DEFAULT '2000-01-01'")));
            assertTrue(plan.statements().stream().noneMatch(sql -> sql.contains("CURRENT_TIMESTAMP")));
        }
        assertEquals("pending", pending.getDefaultValue(), "Normalization must not mutate caller columns");
    }

    @Test void completeBatchLimitIsCheckedBeforeAnySqlCanBeExposed() {
        var columns = java.util.stream.IntStream.range(0, 249).mapToObj(i ->
                column("f" + i, "varchar", 20, null, null, true, false, "'x'", "comment")).toList();
        var accepted = SchemaSynchronization.createPlan(new DatabasePostgresImpl(), "public", "target", "comment", columns);
        assertTrue(accepted.ready(), accepted.diagnostics().toString());
        assertEquals(500, accepted.statements().size());
        var excessive = new java.util.ArrayList<>(columns);
        excessive.add(column("overflow", "varchar", 20, null, null, true, false, "'x'", "comment"));
        var rejected = SchemaSynchronization.createPlan(new DatabasePostgresImpl(), "public", "target", "comment", excessive);
        assertFalse(rejected.ready()); assertTrue(rejected.statements().isEmpty());
    }

    @Test void decimalProductLimitsAreRejectedBeforeCreateOrSynchronizationExposeSql() {
        var mysql = new DatabaseMysqlImpl();
        var postgres = new DatabasePostgresImpl();
        assertTrue(SchemaSynchronization.createPlan(mysql, "test", "target", "",
                List.of(column("amount", "decimal", null, 65, 30, true, false, null, ""))).ready());
        assertTrue(SchemaSynchronization.createPlan(postgres, "public", "target", "",
                List.of(column("amount", "decimal", null, 1000, 1000, true, false, null, ""))).ready());
        for (var invalid : List.of(column("amount", "decimal", null, 66, 0, true, false, null, ""),
                column("amount", "decimal", null, 40, 31, true, false, null, ""))) {
            var rejected = SchemaSynchronization.createPlan(mysql, "test", "target", "", List.of(invalid));
            assertFalse(rejected.ready()); assertTrue(rejected.statements().isEmpty());
        }
        var excessive = column("amount", "decimal", null, 1001, 0, true, false, null, "");
        var create = SchemaSynchronization.createPlan(postgres, "public", "target", "", List.of(excessive));
        assertFalse(create.ready()); assertTrue(create.statements().isEmpty());
        var synchronization = SchemaSynchronization.planObserved(postgres, "public", "target", "", List.of(excessive),
                MetadataSchemaPlanTest.snapshot(nativeColumn("amount", "numeric(12,2)", MetadataValue.absent())), facts(null));
        assertFalse(synchronization.ready()); assertTrue(synchronization.statements().isEmpty());
        assertEquals("UNSUPPORTED_OR_INVALID_COLUMN_DEFINITION", synchronization.diagnostics().getFirst().code());
    }

    @Test void currentTimeAndQuotedLiteralDefaultsAreBoundedExpressions() {
        assertEquals("CURRENT_TIMESTAMP", SchemaSynchronization.defaultExpression(ProductTypeEnum.POSTGRESQL, "current_timestamp"));
        assertEquals("NOW()", SchemaSynchronization.defaultExpression(ProductTypeEnum.POSTGRESQL, "now()"));
        assertEquals("'a''b; literal'", SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL, "'a''b; literal'"));
        assertThrows(IllegalArgumentException.class, () -> SchemaSynchronization.defaultExpression(ProductTypeEnum.MYSQL, "current_timestamp(7)"));
        assertThrows(IllegalArgumentException.class, () -> SchemaSynchronization.defaultExpression(ProductTypeEnum.POSTGRESQL, "current_timestamp--comment"));
    }

    static SchemaSynchronization.TableFacts facts(String key) { return new SchemaSynchronization.TableFacts(key, "", true, false); }

    private static ColumnDescription nativeColumn(String name, String type, MetadataValue<String> defaultValue) {
        var column = MetadataSchemaPlanTest.column(name, type, defaultValue);
        var evidence = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(evidence.provenance(), evidence.nativeType(), evidence.defaultExpression(),
                evidence.nullable(), evidence.numericPrecision(), evidence.numericScale(), evidence.characterLength(),
                evidence.identity(), evidence.generatedExpression(), evidence.charset(), evidence.collation(), MetadataValue.absent()));
        return column;
    }

    static ColumnDescription column(String name, String type, Integer length, Integer precision, Integer scale,
                                    boolean nullable, boolean primary, String defaultValue, String comment) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        SqlTypeSemantics.applyDeclaredType(column, type, length, precision, scale);
        if (length == null && List.of("timestamp", "time", "date").contains(SqlTypeSemantics.canonicalName(type)))
            column.setLength(0);
        column.setNullable(nullable); column.setPk(primary); column.setDefaultValue(defaultValue); column.setRemarks(comment);
        return column;
    }
}
