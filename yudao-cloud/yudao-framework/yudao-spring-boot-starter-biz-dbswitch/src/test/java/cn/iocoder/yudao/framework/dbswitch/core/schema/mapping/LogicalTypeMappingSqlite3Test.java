package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLITE3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 3.31.1 (sqlite-jdbc 3.31.1, file database) logical type mapping. Golden values come from the temporary
 * file probe, not from another product. No connection is opened here.
 */
@Timeout(60)
class LogicalTypeMappingSqlite3Test {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheSqliteMapper() {
        var mapper = REGISTRY.typeMapper(SQLITE3).orElseThrow();
        assertInstanceOf(Sqlite3LogicalTypeMapper.class, mapper);
        assertEquals(SQLITE3, mapper.product());
        assertTrue(MAPPINGS.supports(SQLITE3, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, SQLITE3));
        assertFalse(MAPPINGS.supports(SQLITE3, SQLITE3));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "INTEGER; LOSSY|bigint|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL",
            "integer; LOSSY|bigint|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL",
            "REAL; LOSSY|double precision|DECLARED_TYPE_AFFINITY_NOT_STRICT,NAN_STORED_AS_NULL",
            "TEXT; LOSSY|text COLLATE \"C\"|DECLARED_TYPE_AFFINITY_NOT_STRICT",
            "VARCHAR(20); LOSSY|character varying(20) COLLATE \"C\"|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED",
            "CHAR(5); LOSSY|character(5) COLLATE \"C\"|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED,PAD_SPACE_EQUALITY_CHANGED",
            "BLOB; UNSUPPORTED|-|BLOB_AFFINITY_MIXED_STORAGE",
            "NUMERIC; UNSUPPORTED|-|NUMERIC_AFFINITY_MIXED_STORAGE",
            "DECIMAL(12,2); UNSUPPORTED|-|DECIMAL_STORED_AS_REAL_OR_TEXT",
            "decimal(12, 2); UNSUPPORTED|-|DECIMAL_STORED_AS_REAL_OR_TEXT",
            "DECIMAL(5,9); UNSUPPORTED|-|DECIMAL_STORED_AS_REAL_OR_TEXT",
            "BOOLEAN; UNSUPPORTED|-|BOOLEAN_IS_NUMERIC_AFFINITY",
            "DATE; UNSUPPORTED|-|DATE_STORED_AS_TEXT_OR_NUMBER",
            "DATETIME; UNSUPPORTED|-|DATETIME_STORED_AS_TEXT_OR_NUMBER",
            "VARCHAR(0); UNSUPPORTED|-|ZERO_LENGTH_NOT_A_DOMAIN"})
    void sqliteSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                Sqlite3MappingFixtures.column("v", nativeType), false)));
    }

    @Test void sqliteTextKeyIsNotAStrictCharacterKey() {
        assertEquals("UNSUPPORTED|-|DECLARED_TYPE_AFFINITY_NOT_STRICT,TEXT_KEY_EQUALITY_UNPROVEN",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, Sqlite3MappingFixtures.column("k", "TEXT"), true)));
        assertEquals("LOSSY|bigint|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, Sqlite3MappingFixtures.column("k", "INTEGER"), true)));
    }

    @Test void unprobedDeclaredNamesStayUnrecognized() {
        assertEquals("UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                Sqlite3MappingFixtures.observed("v", "INT", Types.INTEGER, "INT", false, 0, 0), false)));
        assertEquals("UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                Sqlite3MappingFixtures.observed("v", "", Types.NUMERIC, "NUMERIC", true, 0, 0), false)));
    }

    @Test void driverShapeAndProvenanceFailClosed() {
        var wrongClass = Sqlite3MappingFixtures.column("v", "INTEGER");
        wrongClass.setFiledTypeClassName("java.lang.Long");
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongClass, false)));
        var wrongPrecision = Sqlite3MappingFixtures.column("v", "INTEGER");
        wrongPrecision.setPrecisionSize(19);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongPrecision, false)));
        var decimalSigned = Sqlite3MappingFixtures.column("v", "DECIMAL(12,2)");
        decimalSigned.setSigned(true);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, decimalSigned, false)));
        var datetime = Sqlite3MappingFixtures.column("v", "DATETIME");
        datetime.setFieldType(Types.TIMESTAMP);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, datetime, false)));
        var missingLength = Sqlite3MappingFixtures.withFacts(Sqlite3MappingFixtures.column("v", "VARCHAR(20)"),
                facts -> new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                        facts.nullable(), facts.numericPrecision(), facts.numericScale(), MetadataValue.unknown(),
                        facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, missingLength, false)));
        var collated = Sqlite3MappingFixtures.withFacts(Sqlite3MappingFixtures.column("v", "TEXT"),
                facts -> new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                        facts.nullable(), facts.numericPrecision(), facts.numericScale(), facts.characterLength(),
                        facts.identity(), facts.generatedExpression(), facts.charset(), MetadataValue.value("NOCASE"), facts.sequence()));
        assertEquals("UNSUPPORTED|-|COLLATION_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, collated, false)));
        var foreign = Sqlite3MappingFixtures.withFacts(Sqlite3MappingFixtures.column("v", "INTEGER"),
                facts -> new ColumnMetadataEvidence(new MetadataProvenance(SQLITE3, "SQLite", "3.31.1", "NATIVE_CATALOG_V1", Instant.EPOCH),
                        facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                        facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        var otherVersion = Sqlite3MappingFixtures.withFacts(Sqlite3MappingFixtures.column("v", "INTEGER"),
                facts -> new ColumnMetadataEvidence(new MetadataProvenance(SQLITE3, "SQLite", "3.49.2", "SQLITE_CATALOG_V1", Instant.EPOCH),
                        facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                        facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, otherVersion, false)));
    }

    @ParameterizedTest(name = "{0} -> SQLITE3")
    @CsvSource(delimiter = ';', value = {
            "smallint; LOSSY|INTEGER|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL,INTEGER_WIDTH_NOT_ENFORCED",
            "integer; LOSSY|INTEGER|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL,INTEGER_WIDTH_NOT_ENFORCED",
            "bigint; LOSSY|INTEGER|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL",
            "numeric(38,18); UNSUPPORTED|-|DECIMAL_STORED_AS_REAL_OR_TEXT",
            "numeric; UNSUPPORTED|-|NUMERIC_AFFINITY_MIXED_STORAGE",
            "numeric(1001,0); UNSUPPORTED|-|DECIMAL_STORED_AS_REAL_OR_TEXT",
            "real; LOSSY|REAL|DECLARED_TYPE_AFFINITY_NOT_STRICT,NAN_STORED_AS_NULL,NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|REAL|DECLARED_TYPE_AFFINITY_NOT_STRICT,NAN_STORED_AS_NULL,NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; UNSUPPORTED|-|BOOLEAN_IS_NUMERIC_AFFINITY",
            "character varying(40); LOSSY|VARCHAR(40)|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED",
            "text; LOSSY|TEXT|DECLARED_TYPE_AFFINITY_NOT_STRICT",
            "character(5); LOSSY|CHAR(5)|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED",
            "bytea; LOSSY|BLOB|BLOB_AFFINITY_MIXED_STORAGE",
            "date; UNSUPPORTED|-|DATE_STORED_AS_TEXT_OR_NUMBER",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TYPE_UNMAPPED",
            "timestamp(6) without time zone; UNSUPPORTED|-|DATETIME_STORED_AS_TEXT_OR_NUMBER",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED"})
    void postgresSourceToSqliteGolden(String nativeType, String expected) {
        assertEquals(expected, sqliteTarget(LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false));
    }

    @Test void postgresKeysAndTextSizeOnSqlite() {
        var varchar = LogicalTypeMappingTest.pg("k", "character varying(40)", Types.VARCHAR);
        assertEquals("LOSSY|VARCHAR(40)|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED,PRIMARY_KEY_DUPLICATE_NULLS",
                sqliteTarget(varchar, true));
        assertEquals("LOSSY|VARCHAR(40)|DECLARED_TYPE_AFFINITY_NOT_STRICT,LENGTH_NOT_ENFORCED",
                sqliteTarget(Sqlite3MappingFixtures.notNull(LogicalTypeMappingTest.pg("k", "character varying(40)", Types.VARCHAR)), true));
        assertEquals("LOSSY|INTEGER|DECLARED_TYPE_AFFINITY_NOT_STRICT,INT64_OVERFLOW_STORED_AS_REAL,INTEGER_WIDTH_NOT_ENFORCED",
                sqliteTarget(LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true));
        var text = sqliteTargetResult(LogicalTypeMappingTest.pg("v", "text", Types.VARCHAR), false);
        assertTrue(text.stream().anyMatch(issue -> issue.severity() == TypeMappingIssue.Severity.NOTE
                && issue.code().equals("VALUE_SIZE_LIMITED_BY_TARGET")));
    }

    @Test void plannerQuotesASqliteTarget() {
        var mapping = MAPPINGS.map(SQLITE3, LogicalTypeMappingTest.pg("v", "bigint", Types.BIGINT), false);
        assertEquals("\"v\" INTEGER", mapping.definition());
    }

    /**
     * Mapper-pair result only. It does not render the quoted column definition produced by
     * {@code LogicalTypeMappings.map}.
     */
    private static String sqliteTarget(ColumnDescription column, boolean key) {
        var issues = sqliteTargetResult(column, key);
        var codes = new TreeSet<String>();
        String type = null;
        TypeFidelity fidelity = TypeMappingResult.fidelityOf(issues);
        for (var issue : issues) {
            if (issue.severity() == TypeMappingIssue.Severity.LOSS || issue.severity() == TypeMappingIssue.Severity.BLOCKER)
                codes.add(issue.code());
        }
        codes.remove("TIMESTAMP_JVM_ZONE_DST_SHIFT");
        if (fidelity != TypeFidelity.UNSUPPORTED) {
            var source = MAPPINGS.mapper(column.getMetadataEvidence().provenance().product()).orElseThrow();
            var logical = source.toLogical(column);
            var nativeType = MAPPINGS.mapper(SQLITE3).orElseThrow().toNative(new LogicalColumn(column.getFieldName(),
                    logical.value(), column.isNullable(), key, column.getMetadataEvidence().provenance().product(),
                    column.getMetadataEvidence().nativeType().value()));
            type = nativeType.value().typeSql();
        }
        return fidelity + "|" + (type == null ? "-" : type) + "|" + String.join(",", codes);
    }

    private static List<TypeMappingIssue> sqliteTargetResult(ColumnDescription column, boolean key) {
        var source = MAPPINGS.mapper(column.getMetadataEvidence().provenance().product()).orElseThrow();
        var logical = source.toLogical(column);
        var issues = new ArrayList<>(logical.issues());
        if (logical.fidelity() != TypeFidelity.UNSUPPORTED) {
            issues.addAll(MAPPINGS.mapper(SQLITE3).orElseThrow().toNative(new LogicalColumn(column.getFieldName(),
                    logical.value(), column.isNullable(), key, column.getMetadataEvidence().provenance().product(),
                    column.getMetadataEvidence().nativeType().value())).issues());
        }
        return issues;
    }
}
