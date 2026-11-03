package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MetadataSchemaPlanTest {
    static final MetadataProvenance SOURCE = new MetadataProvenance(ProductTypeEnum.POSTGRESQL,
            "PostgreSQL", "18.0", "TEST_NATIVE_CATALOG", Instant.parse("2026-10-03T00:00:00Z"));

    @Test void defaultExpressionsAndLargeDecimalPrecisionAreNotCleaned() {
        for (String expression : List.of("('a  (::)b'::text)", "now()", "nextval('sequence'::regclass)", "N'(value)'", "b'1001'")) {
            ColumnDescription column = column("value", "text", MetadataValue.value(expression));
            assertEquals(expression, column.getMetaData().getDefaultValue());
        }
        ColumnDescription decimal = column("amount", "numeric(200,40)", MetadataValue.absent());
        decimal.setFieldType(Types.NUMERIC);
        decimal.setPrecisionSize(200);
        decimal.setScaleSize(40);
        assertEquals(200, decimal.getMetaData().getLength());
        assertEquals(40, decimal.getMetaData().getPrecision());
        decimal.setFieldType(Types.OTHER);
        assertEquals(ColumnMetaData.TYPE_NONE, decimal.getMetaData().getType());
    }

    @Test void unknownDefaultNeverBecomesDropDefault() {
        ColumnDescription want = column("value", "text", MetadataValue.absent());
        ColumnDescription have = column("value", "text", MetadataValue.unknown());
        DdlPlan plan = diff(snapshot(want), snapshot(have));
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals("INCOMPLETE_OR_UNSUPPORTED_COLUMN", plan.diagnostics().getFirst().code());
    }

    @Test void literalWhitespaceAndQuotedNameCaseRemainDifferentInHashAndDiff() {
        var one = snapshot(column("Value", "text", MetadataValue.value("'a  b'::text")));
        var two = snapshot(column("Value", "text", MetadataValue.value("'a b'::text")));
        assertNotEquals(SchemaFingerprint.of(one), SchemaFingerprint.of(two));
        assertEquals(DdlPlan.Kind.SET_DEFAULT, diff(two, one).steps().getFirst().kind());
        assertFalse(diff(snapshot(column("value", "text", MetadataValue.absent())), one).ready());
    }

    @Test void createsThroughExistingGeneratorAndPreservesCompositeKeyOrder() {
        TableSchemaMetadata desired = new TableSchemaMetadata(List.of(column("a", "integer", MetadataValue.absent()),
                column("b", "integer", MetadataValue.absent())), List.of("b", "a"), SOURCE,
                MetadataValue.value(true), MetadataValue.absent());
        String sql = GenerateSqlUtils.getDDLCreateTableSQL(new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), desired, "public", "target");
        assertTrue(sql.contains("PRIMARY KEY (\"b\", \"a\")"));
        TableSchemaMetadata missing = new TableSchemaMetadata(List.of(), List.of(), SOURCE,
                MetadataValue.value(false), MetadataValue.absent());
        DdlPlan plan = diff(desired, missing);
        assertTrue(plan.ready());
        assertEquals(DdlPlan.Kind.CREATE_TABLE, plan.steps().getFirst().kind());
        assertEquals(DdlPlan.RollbackCompleteness.STRUCTURE_ONLY_DATA_NOT_RESTORED,
                plan.steps().getFirst().rollbackCompleteness());
    }

    @Test void unknownExistenceCannotGenerateCreate() {
        TableSchemaMetadata missing = new TableSchemaMetadata(List.of(), List.of(), SOURCE,
                MetadataValue.unknown(), MetadataValue.absent());
        assertFalse(diff(snapshot(column("value", "text", MetadataValue.absent())), missing).ready());
    }

    @Test void requestedIdentityGenerationCannotBeSilentlyIgnoredForNativeColumns() {
        var dialect = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl();
        var id = column("id", "integer", MetadataValue.absent());
        assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                dialect, List.of(id), List.of("id"), "public", "target", true));
        String unchanged = GenerateSqlUtils.getDDLCreateTableSQL(
                dialect, List.of(id), List.of("id"), "public", "target", false);
        assertTrue(unchanged.contains("\"id\" integer"));
        assertFalse(unchanged.contains("IDENTITY"));
        assertEquals(MetadataValue.State.ABSENT, id.getMetadataEvidence().identity().state());
    }

    @Test void wideningIsExplicitAndNarrowingAndRenameAreBlocked() {
        var ten = snapshot(column("value", "character varying(10)", MetadataValue.absent()));
        var twenty = snapshot(column("value", "character varying(20)", MetadataValue.absent()));
        assertEquals(DdlPlan.Kind.WIDEN_TYPE, diff(twenty, ten).steps().getFirst().kind());
        assertFalse(diff(ten, twenty).ready());
        assertFalse(diff(snapshot(column("new_name", "character varying(10)", MetadataValue.absent())), ten).ready());
    }

    @Test void metadataSnapshotCopiesMutableColumnsOnBothBoundaries() {
        ColumnDescription column = column("value", "text", MetadataValue.absent());
        TableSchemaMetadata snapshot = snapshot(column);
        column.setFieldName("outside_mutation");
        snapshot.columns().getFirst().setFieldName("accessor_mutation");
        assertEquals("value", snapshot.columns().getFirst().getFieldName());
    }

    @Test void legacyMetadataAndCrossProductProvenanceCannotClaimNativeCreate() {
        ColumnDescription legacy = new ColumnDescription();
        assertEquals(MetadataValue.State.UNKNOWN, legacy.getMetadataEvidence().defaultExpression().state());
        assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl(), snapshot(column("value", "text", MetadataValue.absent())), "db", "table"));
        assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), snapshot(column("id", "bigint", MetadataValue.value("nextval('seq'::regclass)"))), "public", "table"));
    }

    @Test void cyclesAndUnknownStepDependenciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DdlPlan.ordered(List.of(step("a", "b"), step("b", "a"))));
        assertThrows(IllegalArgumentException.class, () -> DdlPlan.ordered(List.of(step("a", "missing"))));
        assertEquals(List.of("a", "b"), DdlPlan.ordered(List.of(step("b", "a"), step("a"))).stream().map(DdlPlan.Step::id).toList());
    }

    private static DdlPlan.Step step(String id, String... dependencies) {
        return new DdlPlan.Step(id, DdlPlan.Kind.ADD_COLUMN, "value", "sql", List.of(dependencies), Set.of(),
                DdlPlan.Transactionality.TRANSACTIONAL, "reverse", DdlPlan.RollbackCompleteness.CONDITIONAL_STRUCTURE);
    }
    private static DdlPlan diff(TableSchemaMetadata desired, TableSchemaMetadata actual) {
        return new SchemaDiff().plan(new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl(), "public", "target", desired, actual);
    }
    static TableSchemaMetadata snapshot(ColumnDescription... columns) {
        return new TableSchemaMetadata(List.of(columns), List.of(), SOURCE, MetadataValue.value(true), MetadataValue.absent());
    }
    static ColumnDescription column(String name, String type, MetadataValue<String> defaultValue) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(ProductTypeEnum.POSTGRESQL);
        column.setFieldType(Types.VARCHAR);
        column.setDefaultValue(defaultValue.value());
        column.setNullable(true);
        column.setMetadataEvidence(new ColumnMetadataEvidence(SOURCE, MetadataValue.value(type), defaultValue,
                MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }
}
