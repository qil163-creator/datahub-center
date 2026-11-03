package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2FamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.mysql.MysqlFamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleFamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerFamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseFamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductDescriptor;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** Golden table of the cross-product logical type mapping (no database). Expected values come from the probed servers. */
class LogicalTypeMappingTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    /** Drivers that materialize java.sql.Timestamp shift wall times only where the JVM zone has DST gaps. */
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();

    // ---------------------------------------------------------------- registry / descriptor SPI

    @Test void registryPublishesExactlyThePgFamilyMappersAndOtherProductsKeepRejecting() {
        assertEquals(EnumSet.of(POSTGRESQL, MYSQL, MARIADB, TIDB, HIGHGO, UXDB, GREENPLUM, GBASE8A, DORIS,
                ORACLE, KINGBASE, DM, SQLSERVER, SYBASE, DB2, STARROCKS, CLICKHOUSE, TDENGINE, INFLUXDB3,
                SQLITE3, OPENGAUSS, OCEANBASE, OSCAR, SQLSERVER2000, HIVE), EnumSet.copyOf(MAPPINGS.products()));
        assertInstanceOf(PostgresLogicalTypeMapper.class, REGISTRY.typeMapper(POSTGRESQL).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.gbase8a.Gbase8aLogicalTypeMapper.class,
                REGISTRY.typeMapper(GBASE8A).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.doris.DorisLogicalTypeMapper.class,
                REGISTRY.typeMapper(DORIS).orElseThrow());
        assertEquals(MysqlFamilyLogicalTypeMapper.Flavor.TIDB, ((MysqlFamilyLogicalTypeMapper) REGISTRY.typeMapper(TIDB).orElseThrow()).flavor());
        assertEquals(PgFamilyLogicalTypeMapper.Flavor.HIGHGO, ((PgFamilyLogicalTypeMapper) REGISTRY.typeMapper(HIGHGO).orElseThrow()).flavor());
        assertEquals(PgFamilyLogicalTypeMapper.Flavor.UXDB, ((PgFamilyLogicalTypeMapper) REGISTRY.typeMapper(UXDB).orElseThrow()).flavor());
        assertEquals(PgFamilyLogicalTypeMapper.Flavor.GREENPLUM, ((PgFamilyLogicalTypeMapper) REGISTRY.typeMapper(GREENPLUM).orElseThrow()).flavor());
        assertInstanceOf(OracleFamilyLogicalTypeMapper.class, REGISTRY.typeMapper(ORACLE).orElseThrow());
        assertEquals(OracleFamilyLogicalTypeMapper.Flavor.KINGBASE,
                ((OracleFamilyLogicalTypeMapper) REGISTRY.typeMapper(KINGBASE).orElseThrow()).flavor());
        assertEquals(OracleFamilyLogicalTypeMapper.Flavor.DM,
                ((OracleFamilyLogicalTypeMapper) REGISTRY.typeMapper(DM).orElseThrow()).flavor());
        assertEquals(SqlServerFamilyLogicalTypeMapper.Flavor.SQLSERVER,
                ((SqlServerFamilyLogicalTypeMapper) REGISTRY.typeMapper(SQLSERVER).orElseThrow()).flavor());
        assertEquals(SybaseFamilyLogicalTypeMapper.Flavor.SYBASE,
                ((SybaseFamilyLogicalTypeMapper) REGISTRY.typeMapper(SYBASE).orElseThrow()).flavor());
        assertEquals(Db2FamilyLogicalTypeMapper.Flavor.DB2,
                ((Db2FamilyLogicalTypeMapper) REGISTRY.typeMapper(DB2).orElseThrow()).flavor());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksLogicalTypeMapper.class,
                REGISTRY.typeMapper(STARROCKS).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickHouseLogicalTypeMapper.class,
                REGISTRY.typeMapper(CLICKHOUSE).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineLogicalTypeMapper.class,
                REGISTRY.typeMapper(TDENGINE).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxDb3LogicalTypeMapper.class,
                REGISTRY.typeMapper(INFLUXDB3).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3LogicalTypeMapper.class,
                REGISTRY.typeMapper(SQLITE3).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.opengauss.OpengaussLogicalTypeMapper.class,
                REGISTRY.typeMapper(OPENGAUSS).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseLogicalTypeMapper.class,
                REGISTRY.typeMapper(OCEANBASE).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarLogicalTypeMapper.class,
                REGISTRY.typeMapper(OSCAR).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.Sqlserver2000LogicalTypeMapper.class,
                REGISTRY.typeMapper(SQLSERVER2000).orElseThrow());
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.product.hive.HiveLogicalTypeMapper.class,
                REGISTRY.typeMapper(HIVE).orElseThrow());
        for (var product : List.of(MONGODB, ELASTICSEARCH))
            assertTrue(REGISTRY.typeMapper(product).isEmpty(), product.name());
        assertTrue(MAPPINGS.supports(ORACLE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(KINGBASE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(DM, POSTGRESQL));
        assertTrue(MAPPINGS.supports(GBASE8A, POSTGRESQL));
        assertTrue(MAPPINGS.supports(DORIS, POSTGRESQL));
        assertTrue(MAPPINGS.supports(MYSQL, POSTGRESQL));
        assertFalse(MAPPINGS.supports(MYSQL, MYSQL));
        assertTrue(MAPPINGS.supports(SQLSERVER, POSTGRESQL));
        assertTrue(MAPPINGS.supports(SYBASE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(DB2, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, SYBASE));
        assertFalse(MAPPINGS.supports(SYBASE, SYBASE));
        assertFalse(MAPPINGS.supports(DB2, DB2));
        var oracle = MAPPINGS.map(ORACLE, pg("v", "integer", Types.INTEGER), false);
        assertEquals("EXACT|NUMBER(10,0)|", outcome(oracle));
    }

    @Test void descriptorsCarryMappersImmutablyWithoutChangingCapabilities() {
        var plain = DbSwitchProductDescriptor.builder(POSTGRESQL).metadata(cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl::new)
                .declare(DatabaseOperation.DDL_CREATE).build();
        var mapper = new PostgresLogicalTypeMapper();
        var mapped = plain.withTypeMapper(mapper);
        assertTrue(plain.typeMapper().isEmpty());
        assertSame(mapper, mapped.typeMapper().orElseThrow());
        assertEquals(plain.capabilities(), mapped.capabilities());
        assertThrows(IllegalArgumentException.class, () -> mapped.withTypeMapper(new PostgresLogicalTypeMapper()));
        assertThrows(IllegalArgumentException.class, () -> plain.withTypeMapper(new MysqlFamilyLogicalTypeMapper(MysqlFamilyLogicalTypeMapper.Flavor.MYSQL)));
        assertThrows(IllegalArgumentException.class, () -> DbSwitchProductDescriptor.builder(POSTGRESQL).typeMapper(mapper).typeMapper(mapper));
        var registry = new DbSwitchProductRegistry(List.of(mapped));
        assertEquals(Set.of(POSTGRESQL), registry.typeMappings().products());
        assertTrue(new DbSwitchProductRegistry(List.of(plain)).typeMappings().products().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> LogicalTypeMappings.of(List.of(mapper, new PostgresLogicalTypeMapper())));
        for (var descriptor : REGISTRY.products()) // Mapper registration must not alter the shipped capability matrix.
            assertEquals(DatabaseOperation.values().length, descriptor.capabilities().size());
    }

    // ---------------------------------------------------------------- PostgreSQL source golden

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = ';', value = {
            "smallint; MYSQL; EXACT|smallint|", "smallint; MARIADB; EXACT|smallint|", "integer; TIDB; EXACT|int|",
            "bigint; MYSQL; EXACT|bigint|",
            "numeric(65,30); MYSQL; EXACT|decimal(65,30)|", "numeric(65,30); TIDB; EXACT|decimal(65,30)|",
            "numeric(65,30); MARIADB; LOSSY|decimal(65,30)|NAN_NOT_REPRESENTABLE",
            "numeric(66,0); MYSQL; UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); MYSQL; UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric; TIDB; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(3,-2); MYSQL; UNSUPPORTED|-|TYPE_UNMAPPED",
            "real; MYSQL; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED,NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; MARIADB; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; TIDB; EXACT|tinyint(1)|",
            "character varying(40); MYSQL; EXACT|varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin|",
            "character varying(40); MARIADB; EXACT|varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin|",
            "character varying(40); TIDB; EXACT|varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin|",
            "character varying(16384); MYSQL; UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "text; MYSQL; EXACT|longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin|",
            "character varying; TIDB; EXACT|longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin|",
            "character(5); MARIADB; EXACT|char(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|",
            "character(256); MYSQL; UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "bytea; TIDB; EXACT|longblob|",
            "date; MYSQL; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; MARIADB; LOSSY|time(3)|TIME_24_00_WRAPPED",
            "time without time zone; MYSQL; LOSSY|time(6)|TIME_24_00_WRAPPED",
            "time with time zone; MYSQL; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) with time zone; TIDB; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; MARIADB; EXACT|uuid|", "uuid; MYSQL; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE", "uuid; TIDB; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; MARIADB; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "jsonb; MYSQL; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; MYSQL; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; TIDB; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; MYSQL; UNSUPPORTED|-|TYPE_UNMAPPED", "money; MYSQL; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "inet; MARIADB; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED", "bit(3); MYSQL; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceGolden(String nativeType, ProductTypeEnum target, String expected) {
        assertEquals(expected, outcome(MAPPINGS.map(target, pg("v", nativeType, pgJdbc(nativeType)), false)));
    }

    @Test void postgresTimestampLossesIncludeTheJvmZoneCodec() {
        var mapping = MAPPINGS.map(MYSQL, pg("v", "timestamp(3) without time zone", Types.TIMESTAMP), false);
        assertEquals("datetime(3)", mapping.targetNativeType());
        assertEquals(ZONE_GAPS ? Set.of("DATE_RANGE_NOT_REPRESENTABLE", "TIMESTAMP_JVM_ZONE_DST_SHIFT") : Set.of("DATE_RANGE_NOT_REPRESENTABLE"), mapping.lossCodes());
    }

    @Test void postgresCollationKeysSequencesAndInconsistentFactsAreBlocked() {
        var c = pg("v", "character varying(10)", Types.VARCHAR);
        withFacts(c, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), MetadataValue.value("pg_catalog.\"C\""), f.sequence()));
        assertEquals("EXACT|varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin|", outcome(MAPPINGS.map(MYSQL, c, false)));
        withFacts(c, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), MetadataValue.value("public.\"en-x-icu\""), f.sequence()));
        assertEquals("UNSUPPORTED|-|COLLATION_UNVERIFIED", outcome(MAPPINGS.map(MYSQL, c, false)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", outcome(MAPPINGS.map(TIDB, pg("v", "character varying(10)", Types.VARCHAR), true)));
        var shared = pg("v", "bigint", Types.BIGINT);
        withFacts(shared, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), f.collation(), MetadataValue.unsupported()));
        assertEquals("UNSUPPORTED|-|SEQUENCE_DEPENDENCY_UNMAPPED", outcome(MAPPINGS.map(MYSQL, shared, false)));
        var unobserved = pg("v", "bigint", Types.BIGINT);
        withFacts(unobserved, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), f.collation(), MetadataValue.unknown()));
        var mapping = MAPPINGS.map(MYSQL, unobserved, false);
        assertEquals(TypeFidelity.EXACT, mapping.fidelity());
        assertTrue(mapping.codes(TypeMappingIssue.Severity.NOTE).contains("SEQUENCE_DEPENDENCY_UNOBSERVED"));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", outcome(MAPPINGS.map(MYSQL, pg("v", "smallint", Types.INTEGER), false)));
        var unknownLength = pg("v", "character varying(10)", Types.VARCHAR);
        withFacts(unknownLength, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), MetadataValue.unknown(),
                f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA", outcome(MAPPINGS.map(MYSQL, unknownLength, false)));
        var unknownScale = pg("v", "numeric(10,2)", Types.NUMERIC);
        withFacts(unknownScale, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), MetadataValue.unknown(), f.characterLength(),
                f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA", outcome(MAPPINGS.map(MYSQL, unknownScale, false)));
        var foreign = pg("v", "integer", Types.INTEGER);
        withFacts(foreign, f -> new ColumnMetadataEvidence(new MetadataProvenance(POSTGRESQL, "openGauss", "7.0", "NATIVE_CATALOG_V1", Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(),
                f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED", outcome(MAPPINGS.map(MYSQL, foreign, false)));
    }

    // ---------------------------------------------------------------- MySQL family source golden (to PostgreSQL)

    @ParameterizedTest(name = "{0} {1} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; tinyint; EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "MARIADB; tinyint(4); EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "TIDB; tinyint unsigned; EXACT|smallint|; \"v\" BETWEEN 0 AND 255",
            "MYSQL; smallint unsigned; EXACT|integer|; \"v\" BETWEEN 0 AND 65535",
            "MARIADB; mediumint(9); EXACT|integer|; \"v\" BETWEEN -8388608 AND 8388607",
            "MARIADB; mediumint(8) unsigned; EXACT|integer|; \"v\" BETWEEN 0 AND 16777215",
            "TIDB; int unsigned; EXACT|bigint|; \"v\" BETWEEN 0 AND 4294967295",
            "MYSQL; bigint unsigned; EXACT|numeric(20,0)|; \"v\" BETWEEN 0 AND 18446744073709551615",
            "TIDB; bigint; EXACT|bigint|; -",
            "MYSQL; decimal(12,2) unsigned; EXACT|numeric(12,2)|; \"v\" >= 0",
            "TIDB; decimal(12,2) unsigned; EXACT|numeric(12,2)|; \"v\" >= 0",
            "MARIADB; decimal(65,30); EXACT|numeric(65,30)|; -",
            "MYSQL; double; EXACT|double precision|; -",
            "MARIADB; double unsigned; EXACT|double precision|; \"v\" >= 0",
            "MYSQL; float; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED; -",
            "TIDB; float unsigned; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED; \"v\" >= 0",
            "MYSQL; binary(4); EXACT|bytea|; pg_catalog.octet_length(\"v\") = 4",
            "MARIADB; varbinary(9); EXACT|bytea|; pg_catalog.octet_length(\"v\") <= 9",
            "TIDB; longblob; EXACT|bytea|; -",
            "MYSQL; date; EXACT|date|; -",
            "TIDB; datetime(6); EXACT|timestamp(6) without time zone|; -",
            "MYSQL; time(3); EXACT|time(3) without time zone|; -",
            "TIDB; time(6); LOSSY|time(6) without time zone|TIME_FRACTION_TRUNCATED; -",
            "MARIADB; time(3); LOSSY|time(3) without time zone|TIME_DURATION_WRAPPED; -",
            "MARIADB; date; LOSSY|date|ZERO_DATE_SILENTLY_CONVERTED; -",
            "MYSQL; timestamp(3); UNSUPPORTED|-|TIMESTAMP_SESSION_ZONE_CODEC_UNVERIFIED; -",
            "MYSQL; json; EXACT|jsonb|; -", "TIDB; json; EXACT|jsonb|; -",
            "MARIADB; uuid; EXACT|uuid|; -",
            "MYSQL; float(7,2); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "MYSQL; bit(3); UNSUPPORTED|-|BOOLEAN_DRIVER_COERCION; -",
            "TIDB; year(4); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "MYSQL; enum('a','b'); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "MYSQL; geometry; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED; -",
            "MYSQL; int unsigned zerofill; UNSUPPORTED|-|TYPE_UNMAPPED; -"})
    void mysqlFamilySourceToPostgresGolden(ProductTypeEnum source, String nativeType, String expected, String check) {
        var mapping = MAPPINGS.map(POSTGRESQL, family(source, "v", nativeType), false);
        assertEquals(expected, outcome(mapping));
        if (mapping.definition() != null)
            assertEquals(check.equals("-") ? null : check, mapping.definition().contains(" CHECK (")
                    ? mapping.definition().substring(mapping.definition().indexOf(" CHECK (") + 8, mapping.definition().length() - 1) : null);
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; varchar(20); utf8mb4_0900_ai_ci; POSTGRESQL; LOSSY|character varying(20)|COLLATION_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_bin; POSTGRESQL; LOSSY|character varying(20) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_0900_bin; POSTGRESQL; EXACT|character varying(20) COLLATE \"C\"|",
            "MARIADB; varchar(20); utf8mb4_nopad_bin; POSTGRESQL; EXACT|character varying(20) COLLATE \"C\"|",
            "TIDB; char(5); utf8mb4_bin; POSTGRESQL; EXACT|character(5) COLLATE \"C\"|",
            "MYSQL; char(5); utf8mb4_0900_bin; POSTGRESQL; LOSSY|character(5) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; text; utf8mb4_0900_bin; POSTGRESQL; EXACT|text COLLATE \"C\"|",
            "MYSQL; varchar(20); latin1_swedish_ci; POSTGRESQL; LOSSY|character varying(20)|COLLATION_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_bin; MARIADB; EXACT|varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|",
            "MYSQL; varchar(20); utf8mb4_0900_bin; MARIADB; EXACT|varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin|",
            "MARIADB; varchar(20); utf8mb4_nopad_bin; MYSQL; EXACT|varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin|",
            "MYSQL; varchar(20); utf8mb4_0900_ai_ci; MARIADB; LOSSY|varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci|COLLATION_EQUALITY_UNVERIFIED",
            "MARIADB; varchar(20); utf8mb4_uca1400_ai_ci; MYSQL; UNSUPPORTED|-|COLLATION_UNAVAILABLE",
            "MYSQL; varchar(20); latin1_swedish_ci; TIDB; UNSUPPORTED|-|CHARSET_UNVERIFIED",
            "TIDB; text; utf8mb4_bin; MYSQL; EXACT|text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|",
            "MYSQL; mediumtext; utf8mb4_bin; MARIADB; EXACT|mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|"})
    void characterGolden(ProductTypeEnum source, String nativeType, String collation, ProductTypeEnum target, String expected) {
        assertEquals(expected, outcome(MAPPINGS.map(target, text(source, "v", nativeType, collation), false)));
    }

    @Test void characterKeysNeedProvedEqualityOnBothSides() {
        assertEquals("EXACT|varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|",
                outcome(MAPPINGS.map(MARIADB, text(MYSQL, "k", "varchar(20)", "utf8mb4_bin"), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", outcome(MAPPINGS.map(MARIADB, text(MYSQL, "k", "varchar(20)", "utf8mb4_0900_ai_ci"), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", outcome(MAPPINGS.map(POSTGRESQL, text(MYSQL, "k", "varchar(20)", "utf8mb4_0900_bin"), true)));
        assertEquals("EXACT|bytea|", outcome(MAPPINGS.map(POSTGRESQL, family(MYSQL, "k", "varbinary(9)"), true)).replaceAll("\\|$", "|"));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; int unsigned; MARIADB; EXACT|int unsigned|", "MYSQL; decimal(10,2) unsigned; TIDB; EXACT|decimal(10,2) unsigned|",
            "MYSQL; double; MARIADB; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED", "MARIADB; double; MYSQL; EXACT|double|",
            "TIDB; double; MYSQL; EXACT|double|", "MYSQL; float; TIDB; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED",
            "MYSQL; json; MARIADB; EXACT|json|", "TIDB; json; MYSQL; EXACT|json|", "MYSQL; date; MARIADB; EXACT|date|",
            "MARIADB; date; MYSQL; LOSSY|date|ZERO_DATE_SILENTLY_CONVERTED", "MYSQL; datetime(6); TIDB; EXACT|datetime(6)|",
            "MARIADB; uuid; MYSQL; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE", "MYSQL; binary(4); MARIADB; EXACT|binary(4)|",
            "MYSQL; blob; TIDB; EXACT|blob|", "MYSQL; mediumblob; MARIADB; EXACT|mediumblob|", "TIDB; tinyint(1) unsigned; MYSQL; EXACT|tinyint unsigned|",
            "MYSQL; time(3); MARIADB; EXACT|time(3)|", "MYSQL; timestamp(6); TIDB; UNSUPPORTED|-|TIMESTAMP_SESSION_ZONE_CODEC_UNVERIFIED"})
    void mysqlFamilyToFamilyGolden(ProductTypeEnum source, String nativeType, ProductTypeEnum target, String expected) {
        assertEquals(expected, outcome(MAPPINGS.map(target, family(source, "v", nativeType), false)));
    }

    @Test void mariaDbJsonIsTextPreservingAndItsDatetimeCodecIsLossy() {
        var json = text(MARIADB, "v", "longtext", "utf8mb4_bin");
        json.setFieldTypeName("JSON");
        assertEquals("EXACT|json|", outcome(MAPPINGS.map(POSTGRESQL, json, false)));
        assertEquals("LOSSY|json|JSON_NORMALIZED,JSON_NUMBER_PRECISION", outcome(MAPPINGS.map(MYSQL, json, false)));
        var datetime = MAPPINGS.map(POSTGRESQL, family(MARIADB, "v", "datetime(6)"), false);
        assertEquals(ZONE_GAPS ? Set.of("ZERO_DATE_SILENTLY_CONVERTED", "TIMESTAMP_JVM_ZONE_DST_SHIFT") : Set.of("ZERO_DATE_SILENTLY_CONVERTED"), datetime.lossCodes());
    }

    @Test void flavorSpecificDriverAndCatalogPrecisionAreEnforced() {
        // MariaDB driver precision follows the display width; MySQL ignores it; TiDB catalog precision is the display width.
        var mariaWidth = family(MARIADB, "v", "int(5)"); mariaWidth.setPrecisionSize(5);
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(POSTGRESQL, mariaWidth, false).fidelity());
        var mariaWrong = family(MARIADB, "v", "int(5)"); mariaWrong.setPrecisionSize(10);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", outcome(MAPPINGS.map(POSTGRESQL, mariaWrong, false)));
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(POSTGRESQL, catalogPrecision(family(TIDB, "v", "int"), 5), false).fidelity());
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA", outcome(MAPPINGS.map(POSTGRESQL, catalogUnknown(family(TIDB, "v", "int")), false)));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", outcome(MAPPINGS.map(POSTGRESQL, catalogPrecision(family(MYSQL, "v", "mediumint unsigned"), 8), false)));
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(POSTGRESQL, catalogPrecision(family(MARIADB, "v", "mediumint(8) unsigned"), 8), false).fidelity());
        var tidbDecimal = family(TIDB, "v", "decimal(12,2) unsigned");
        assertEquals(13, tidbDecimal.getPrecisionSize());
        var mysqlDecimal = family(MYSQL, "v", "decimal(12,2) unsigned"); mysqlDecimal.setPrecisionSize(13);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", outcome(MAPPINGS.map(POSTGRESQL, mysqlDecimal, false)));
        var bool = family(MARIADB, "v", "tinyint(1) unsigned"); bool.setFieldType(Types.BOOLEAN); bool.setFiledTypeClassName("java.lang.Boolean");
        assertEquals("UNSUPPORTED|-|BOOLEAN_DRIVER_COERCION", outcome(MAPPINGS.map(POSTGRESQL, bool, false)));
        var maria = family(MARIADB, "v", "int(11)");
        withFacts(maria, f -> new ColumnMetadataEvidence(new MetadataProvenance(MARIADB, "MariaDB", "10.6.21-MariaDB", "MARIADB_CATALOG_V1", Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(),
                f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED", outcome(MAPPINGS.map(POSTGRESQL, maria, false)));
    }

    // ---------------------------------------------------------------- planner generic rules

    @ParameterizedTest
    @CsvSource({"default, DEFAULT_UNMAPPED", "unknown-default, INCOMPLETE_METADATA", "legacy-default, DEFAULT_UNMAPPED",
            "identity, IDENTITY_UNMAPPED", "auto-increment, IDENTITY_UNMAPPED", "generated, GENERATED_UNMAPPED",
            "unknown-generated, INCOMPLETE_METADATA", "unknown-nullable, INCOMPLETE_METADATA", "nullable-mismatch, NULLABILITY_INCONSISTENT",
            "unknown-charset, INCOMPLETE_METADATA", "unknown-collation, INCOMPLETE_METADATA"})
    void plannerBlocksSemanticsWithoutVerifiedCrossProductMapping(String fault, String code) {
        var column = family(MYSQL, "v", "int");
        var f = column.getMetadataEvidence();
        switch (fault) {
            case "legacy-default" -> column.setDefaultValue("7");
            case "auto-increment" -> column.setAutoIncrement(true);
            case "nullable-mismatch" -> column.setNullable(false);
            default -> column.setMetadataEvidence(facts(f, f.nativeType(),
                    fault.equals("default") ? MetadataValue.value("7") : fault.equals("unknown-default") ? MetadataValue.unknown() : f.defaultExpression(),
                    fault.equals("unknown-nullable") ? MetadataValue.unknown() : f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(),
                    fault.equals("identity") ? MetadataValue.value("AUTO_INCREMENT") : f.identity(),
                    fault.equals("generated") ? MetadataValue.value("1+1") : fault.equals("unknown-generated") ? MetadataValue.unknown() : f.generatedExpression(),
                    fault.equals("unknown-charset") ? MetadataValue.unknown() : f.charset(),
                    fault.equals("unknown-collation") ? MetadataValue.unknown() : f.collation(), f.sequence()));
        }
        var mapping = MAPPINGS.map(POSTGRESQL, column, false);
        assertEquals(TypeFidelity.UNSUPPORTED, mapping.fidelity());
        assertTrue(mapping.codes(TypeMappingIssue.Severity.BLOCKER).contains(code), mapping::describe);
        assertNull(mapping.definition());
    }

    @Test void sameProductLogicalFallbackAndMissingMappersAreRejected() {
        var maria = family(MARIADB, "v", "int(11)");
        assertEquals("UNSUPPORTED|-|SAME_PRODUCT_REQUIRES_NATIVE_PROVENANCE", outcome(MAPPINGS.map(MARIADB, maria, false)));
        // OceanBase has a mapper, but the JDBC facade version and NATIVE_CATALOG_V1 are not the MySQL-mode pin.
        var oceanbase = family(MYSQL, "v", "int");
        withFacts(oceanbase, f -> new ColumnMetadataEvidence(new MetadataProvenance(OCEANBASE, "OceanBase", "4.3.5", "NATIVE_CATALOG_V1", Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(),
                f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED", outcome(MAPPINGS.map(POSTGRESQL, oceanbase, false)));
        assertEquals("UNSUPPORTED|-|SOURCE_MAPPER_UNAVAILABLE,TARGET_MAPPER_UNAVAILABLE",
                outcome(LogicalTypeMappings.empty().map(POSTGRESQL, family(MYSQL, "v", "int"), false)));
        var unknownType = family(MYSQL, "v", "int");
        withFacts(unknownType, f -> facts(f, MetadataValue.unknown(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA", outcome(MAPPINGS.map(POSTGRESQL, unknownType, false)));
    }

    // ---------------------------------------------------------------- LOSSY acceptance and the generator

    @Test void lossyColumnsAreRejectedByDefaultAndAcceptedOnlyPerColumnAndPerLossCode() {
        var date = pg("d", "date", Types.DATE);
        var id = pg("id", "integer", Types.INTEGER); id.setNullable(false); notNull(id);
        var mysql = REGISTRY.metadata(MYSQL);
        var rejected = assertThrows(TypeMappingRejectedException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(id, date), List.of("id"), "db", "t", false));
        assertEquals(List.of("d"), rejected.plan().rejectedColumns().stream().map(ColumnTypeMapping::column).toList());
        assertTrue(rejected.getMessage().contains("d [POSTGRESQL date -> MYSQL date] LOSSY") && rejected.getMessage().contains("DATE_RANGE_NOT_REPRESENTABLE"), rejected.getMessage());
        assertFalse(rejected.getMessage().contains("id ["), rejected.getMessage());
        var accept = TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(LossyColumnAcceptance.acknowledged(Map.of("d", Set.of("DATE_RANGE_NOT_REPRESENTABLE"))));
        String ddl = GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(id, date), List.of("id"), "db", "t", false, accept);
        assertTrue(ddl.contains("`id` int NOT NULL") && ddl.contains("`d` date") && ddl.contains("PRIMARY KEY (`id`)"), ddl);
        var otherColumn = TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(LossyColumnAcceptance.acknowledged(Map.of("x", Set.of("DATE_RANGE_NOT_REPRESENTABLE"))));
        assertThrows(TypeMappingRejectedException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(id, date), List.of("id"), "db", "t", false, otherColumn));
        var timestamp = pg("ts", "timestamp(3) without time zone", Types.TIMESTAMP);
        var partial = TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(LossyColumnAcceptance.acknowledged(Map.of("ts", Set.of("DATE_RANGE_NOT_REPRESENTABLE"))));
        if (ZONE_GAPS) assertThrows(TypeMappingRejectedException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(id, timestamp), List.of("id"), "db", "t", false, partial));
        var everything = TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(mapping -> true);
        var json = pg("j", "jsonb", Types.OTHER);
        var unsupported = assertThrows(TypeMappingRejectedException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(id, json), List.of("id"), "db", "t", false, everything));
        assertEquals(TypeFidelity.UNSUPPORTED, unsupported.plan().column("j").fidelity());
        assertThrows(IllegalArgumentException.class, () -> new TableTypeMappingPlan(MYSQL, List.of(MAPPINGS.map(MYSQL, json, false)), Set.of("j")));
    }

    @Test void generatorRendersPlannedColumnsAndKeepsSameProductAndPortablePathsUnchanged() {
        var mysql = REGISTRY.metadata(MYSQL);
        var key = pg("id", "bigint", Types.BIGINT); key.setNullable(false); notNull(key);
        var name = pg("name", "character varying(40)", Types.VARCHAR); name.setRemarks("it's 'ok'");
        var portable = new ColumnDescription(); portable.setFieldName("legacy"); portable.setFieldType(Types.INTEGER); portable.setNullable(true);
        var statements = GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(key, name, portable), List.of("id"), "db", "t", null, false, Map.of());
        String ddl = statements.getFirst();
        assertTrue(ddl.contains("`id` bigint NOT NULL"), ddl);
        assertTrue(ddl.contains("`name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT 'it''s ''ok'''"), ddl);
        assertTrue(ddl.contains("`legacy`"), ddl);
        // Backslash meaning depends on NO_BACKSLASH_ESCAPES; such a comment fails explicitly instead of being dropped.
        name.setRemarks("a\\b");
        assertThrows(IllegalArgumentException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(mysql, List.of(key, name), List.of("id"), "db", "t", null, false, Map.of()));
        var postgres = REGISTRY.metadata(POSTGRESQL);
        var unsigned = family(MYSQL, "Value\"", "tinyint unsigned"); unsigned.setNullable(false); notNull(unsigned);
        String created = GenerateSqlUtils.getDDLCreateTableSQL(postgres, List.of(unsigned), List.of("Value\""), "public", "t", false);
        assertTrue(created.contains("\"Value\"\"\" smallint NOT NULL CHECK (\"Value\"\"\" BETWEEN 0 AND 255)"), created);
        // Same-product native columns never consult the planner (jsonb has no cross-product mapping).
        var native_ = pg("doc", "jsonb", Types.OTHER);
        assertTrue(GenerateSqlUtils.getDDLCreateTableSQL(postgres, List.of(native_), List.of(), "public", "t", false).contains("\"doc\" jsonb"));
        // An explicit empty catalog keeps the historical rejection for products without mappers.
        assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(postgres, List.of(unsigned), List.of(), "public", "t",
                false, TypeMappingOptions.of(LogicalTypeMappings.empty())));
        // Preview reports every cross-product column, including rejected ones, without throwing.
        var preview = GenerateSqlUtils.planCrossProductColumns(mysql, List.of(key, name, pg("j", "json", Types.OTHER), portable), List.of("id"), TypeMappingOptions.defaults());
        assertEquals(Map.of("id", TypeFidelity.EXACT, "name", TypeFidelity.EXACT, "j", TypeFidelity.UNSUPPORTED), preview.fidelityByColumn());
        assertFalse(preview.accepted());
        assertSame(LogicalTypeMappings.installed().mapper(MYSQL).orElseThrow().getClass(), MAPPINGS.mapper(MYSQL).orElseThrow().getClass());
    }

    @Test void mappingNeverMutatesTheObservedColumn() {
        var column = family(MYSQL, "v", "decimal(20,0)");
        var before = column.getMetadataEvidence();
        MAPPINGS.plan(POSTGRESQL, List.of(column), List.of(), LossyColumnAcceptance.rejectAll());
        assertSame(before, column.getMetadataEvidence());
        assertEquals("decimal(20,0)", column.getMetadataEvidence().nativeType().value());
    }

    // ---------------------------------------------------------------- fixtures

    private static String outcome(ColumnTypeMapping mapping) {
        var codes = new TreeSet<String>(mapping.codes(TypeMappingIssue.Severity.LOSS));
        codes.addAll(mapping.codes(TypeMappingIssue.Severity.BLOCKER));
        if (mapping.lossCodes().contains("TIMESTAMP_JVM_ZONE_DST_SHIFT")) codes.remove("TIMESTAMP_JVM_ZONE_DST_SHIFT");
        return mapping.fidelity() + "|" + (mapping.fidelity() == TypeFidelity.UNSUPPORTED || mapping.targetNativeType() == null ? "-" : mapping.targetNativeType())
                + "|" + String.join(",", codes);
    }

    private static int pgJdbc(String type) {
        if (type.endsWith("[]")) return Types.ARRAY;
        return switch (type.replaceAll("\\(.*", "")) {
            case "smallint" -> Types.SMALLINT; case "integer" -> Types.INTEGER; case "bigint" -> Types.BIGINT; case "numeric" -> Types.NUMERIC;
            case "real" -> Types.REAL; case "double precision" -> Types.DOUBLE; case "boolean" -> Types.BIT;
            case "character varying", "text" -> Types.VARCHAR; case "character" -> Types.CHAR; case "bytea" -> Types.BINARY;
            case "date" -> Types.DATE; case "time" -> Types.TIME; case "timestamp" -> Types.TIMESTAMP; case "xml" -> Types.SQLXML;
            default -> type.startsWith("time") ? (type.startsWith("timestamp") ? Types.TIMESTAMP : Types.TIME) : Types.OTHER;
        };
    }

    static ColumnDescription pg(String name, String type, int jdbcType) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setDbType(POSTGRESQL); column.setFieldType(jdbcType); column.setNullable(true);
        var numeric = java.util.regex.Pattern.compile("numeric\\(([0-9]+),(-?[0-9]+)\\)").matcher(type);
        MetadataValue<Integer> precision = MetadataValue.absent(), scale = MetadataValue.absent();
        if (numeric.matches()) {
            int p = Integer.parseInt(numeric.group(1)), s = Integer.parseInt(numeric.group(2));
            column.setPrecisionSize(p); column.setScaleSize(s);
            precision = MetadataValue.value(p); scale = MetadataValue.value(s < 0 ? 2046 : s);
        }
        var sized = java.util.regex.Pattern.compile("(?:character varying|character)\\(([0-9]+)\\)").matcher(type);
        MetadataValue<Long> length = sized.matches() ? MetadataValue.value(Long.parseLong(sized.group(1))) : MetadataValue.absent();
        column.setMetadataEvidence(new ColumnMetadataEvidence(new MetadataProvenance(POSTGRESQL, "PostgreSQL", "16.15", "NATIVE_CATALOG_V1", Instant.EPOCH),
                MetadataValue.value(type), MetadataValue.absent(), MetadataValue.value(true), precision, scale, length,
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }

    /** Facts exactly as the probed MySQL 8.4 / MariaDB 11.4 / TiDB 8.5 catalogs and drivers report a non-character column. */
    static ColumnDescription family(ProductTypeEnum product, String name, String type) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        boolean unsigned = type.contains(" unsigned");
        column.setSigned(!unsigned);
        var integer = java.util.regex.Pattern.compile("(tinyint|smallint|mediumint|int|bigint)(?:\\(([0-9]+)\\))?( unsigned)?").matcher(type);
        var decimal = java.util.regex.Pattern.compile("decimal\\(([0-9]+),([0-9]+)\\)( unsigned)?").matcher(type);
        MetadataValue<Integer> precision = MetadataValue.absent(), scale = MetadataValue.absent();
        MetadataValue<Long> length = MetadataValue.absent();
        if (integer.matches()) {
            String base = integer.group(1);
            int digits = switch (base) { case "tinyint" -> 3; case "smallint" -> 5; case "mediumint" -> unsigned ? 8 : 7; case "int" -> 10; default -> unsigned ? 20 : 19; };
            column.setFieldType(switch (base) { case "tinyint" -> Types.TINYINT; case "smallint" -> Types.SMALLINT; case "bigint" -> Types.BIGINT; default -> Types.INTEGER; });
            column.setPrecisionSize(product == MARIADB && integer.group(2) != null ? Math.min(Integer.parseInt(integer.group(2)), digits) : digits);
            int catalog = switch (product) {
                case MYSQL -> base.equals("mediumint") ? 7 : digits;
                case TIDB -> integer.group(2) != null ? Integer.parseInt(integer.group(2)) : switch (base) {
                    case "tinyint" -> unsigned ? 3 : 4; case "smallint" -> unsigned ? 5 : 6; case "mediumint" -> unsigned ? 8 : 9;
                    case "int" -> unsigned ? 10 : 11; default -> 20; };
                default -> digits;
            };
            precision = MetadataValue.value(catalog); scale = MetadataValue.value(0);
        } else if (decimal.matches()) {
            int p = Integer.parseInt(decimal.group(1)), s = Integer.parseInt(decimal.group(2));
            column.setFieldType(Types.DECIMAL); column.setPrecisionSize(product == TIDB && unsigned ? p + 1 : p); column.setScaleSize(s);
            precision = MetadataValue.value(p); scale = MetadataValue.value(s);
        } else {
            String base = type.replaceAll("[( ].*", "");
            column.setFieldType(switch (base) {
                case "float" -> Types.REAL; case "double" -> Types.DOUBLE; case "binary" -> Types.BINARY; case "varbinary", "tinyblob" -> Types.VARBINARY;
                case "blob", "mediumblob", "longblob" -> Types.LONGVARBINARY; case "date", "year" -> Types.DATE; case "datetime", "timestamp" -> Types.TIMESTAMP;
                case "time" -> Types.TIME; case "json" -> Types.LONGVARCHAR; case "uuid" -> Types.OTHER; case "bit" -> Types.BIT; default -> Types.CHAR;
            });
            if (base.equals("uuid")) column.setFiledTypeClassName("java.util.UUID");
            var sized = java.util.regex.Pattern.compile("(?:binary|varbinary)\\(([0-9]+)\\)").matcher(type);
            if (sized.matches()) length = MetadataValue.value(Long.parseLong(sized.group(1)));
            Map<String, Long> blobs = Map.of("tinyblob", 255L, "blob", 65535L, "mediumblob", 16777215L, "longblob", 4294967295L);
            if (blobs.containsKey(base)) length = MetadataValue.value(blobs.get(base));
            if (base.equals("float") || base.equals("double")) precision = MetadataValue.value(base.equals("float") ? 12 : 22);
        }
        boolean textual = type.startsWith("enum(") || type.startsWith("set(");
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(type), MetadataValue.absent(), MetadataValue.value(true),
                precision, scale, length, MetadataValue.absent(), MetadataValue.absent(),
                textual ? MetadataValue.value("utf8mb4") : MetadataValue.absent(), textual ? MetadataValue.value("utf8mb4_0900_ai_ci") : MetadataValue.absent(),
                MetadataValue.unknown()));
        return column;
    }

    static ColumnDescription text(ProductTypeEnum product, String name, String type, String collation) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setDbType(product); column.setNullable(true);
        Map<String, Long> large = Map.of("tinytext", 255L, "text", 65535L, "mediumtext", 16777215L, "longtext", 4294967295L);
        var sized = java.util.regex.Pattern.compile("(char|varchar)\\(([0-9]+)\\)").matcher(type);
        long length = sized.matches() ? Long.parseLong(sized.group(2)) : large.get(type);
        column.setFieldType(sized.matches() ? (sized.group(1).equals("char") ? Types.CHAR : Types.VARCHAR) : Types.LONGVARCHAR);
        String charset = collation.substring(0, collation.indexOf('_'));
        column.setMetadataEvidence(new ColumnMetadataEvidence(provenance(product), MetadataValue.value(type), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(length), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.value(charset), MetadataValue.value(collation), MetadataValue.unknown()));
        return column;
    }

    private static MetadataProvenance provenance(ProductTypeEnum product) {
        return switch (product) {
            case MYSQL -> new MetadataProvenance(MYSQL, "MySQL", "8.4.8", "NATIVE_CATALOG_V1", Instant.EPOCH);
            case TIDB -> new MetadataProvenance(TIDB, "TiDB", "v8.5.6", "TIDB_CATALOG_V1", Instant.EPOCH);
            case MARIADB -> new MetadataProvenance(MARIADB, "MariaDB", "11.4.13-MariaDB-ubu2404", "MARIADB_CATALOG_V1", Instant.EPOCH);
            default -> throw new IllegalArgumentException(product.name());
        };
    }

    private static ColumnDescription catalogPrecision(ColumnDescription column, int precision) {
        withFacts(column, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), MetadataValue.value(precision), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        return column;
    }

    private static ColumnDescription catalogUnknown(ColumnDescription column) {
        withFacts(column, f -> facts(f, f.nativeType(), f.defaultExpression(), f.nullable(), MetadataValue.unknown(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        return column;
    }

    private static void notNull(ColumnDescription column) {
        withFacts(column, f -> facts(f, f.nativeType(), f.defaultExpression(), MetadataValue.value(false), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
    }

    private static void withFacts(ColumnDescription column, java.util.function.UnaryOperator<ColumnMetadataEvidence> change) {
        column.setMetadataEvidence(change.apply(column.getMetadataEvidence()));
    }

    private static ColumnMetadataEvidence facts(ColumnMetadataEvidence f, MetadataValue<String> type, MetadataValue<String> defaultExpression,
            MetadataValue<Boolean> nullable, MetadataValue<Integer> precision, MetadataValue<Integer> scale, MetadataValue<Long> length,
            MetadataValue<String> identity, MetadataValue<String> generated, MetadataValue<String> charset, MetadataValue<String> collation,
            MetadataValue<ColumnSequenceMetadata> sequence) {
        return new ColumnMetadataEvidence(f.provenance(), type, defaultExpression, nullable, precision, scale, length, identity, generated, charset, collation, sequence);
    }
}
