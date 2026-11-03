package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.BinaryType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.StorageKind;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** DB-less goldens for OceanBase CE 4.3.5.6 MySQL mode. Expectations follow the local probe, not MySQL 8.4. */
class LogicalTypeMappingOceanbaseTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheOceanbaseMapperWithoutUsingTheMysqlFamily() {
        var mapper = REGISTRY.typeMapper(OCEANBASE).orElseThrow();
        assertInstanceOf(OceanbaseLogicalTypeMapper.class, mapper);
        assertEquals(OCEANBASE, mapper.product());
        assertTrue(MAPPINGS.supports(OCEANBASE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(OCEANBASE, MYSQL));
        assertFalse(MAPPINGS.supports(OCEANBASE, OCEANBASE));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint(4); EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "smallint(6); EXACT|smallint|; -",
            "mediumint(9); EXACT|integer|; \"v\" BETWEEN -8388608 AND 8388607",
            "int(11); EXACT|integer|; -",
            "bigint(20); EXACT|bigint|; -",
            "tinyint(3) unsigned; EXACT|smallint|; \"v\" BETWEEN 0 AND 255",
            "smallint(5) unsigned; EXACT|integer|; \"v\" BETWEEN 0 AND 65535",
            "mediumint(8) unsigned; EXACT|integer|; \"v\" BETWEEN 0 AND 16777215",
            "int(10) unsigned; EXACT|bigint|; \"v\" BETWEEN 0 AND 4294967295",
            "bigint(20) unsigned; EXACT|numeric(20,0)|; \"v\" BETWEEN 0 AND 18446744073709551615",
            "decimal(10,2); EXACT|numeric(10,2)|; -",
            "decimal(38,10); EXACT|numeric(38,10)|; -",
            "decimal(65,30); EXACT|numeric(65,30)|; -",
            "float; EXACT|real|; -",
            "double; EXACT|double precision|; -",
            "binary(4); EXACT|bytea|; pg_catalog.octet_length(\"v\") = 4",
            "varbinary(16); EXACT|bytea|; pg_catalog.octet_length(\"v\") <= 16",
            "blob; EXACT|bytea|; -",
            "date; LOSSY|date|ZERO_DATE_READ_AS_NULL; -",
            "datetime(6); LOSSY|timestamp(6) without time zone|ZERO_DATETIME_READ_AS_0002_11_30; -",
            "timestamp(6); UNSUPPORTED|-|TIMESTAMP_VALUE_UNPROBED; -",
            "time(6); UNSUPPORTED|-|TIME_VALUE_UNPROBED; -",
            "json; UNSUPPORTED|-|JSON_NUMBER_DOMAIN_UNVERIFIED; -",
            "tinyint(1); LOSSY|boolean|TINYINT1_NONZERO_READ_AS_TRUE; -",
            "tinyint(2); UNSUPPORTED|-|DISPLAY_WIDTH_UNVERIFIED; -",
            "decimal(65,31); UNSUPPORTED|-|NUMERIC_SCALE_UNVERIFIED; -",
            "decimal(66,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET; -",
            "text; UNSUPPORTED|-|TYPE_UNMAPPED; -"})
    void oceanbaseSourceToPostgresGolden(String nativeType, String expected, String check) {
        var source = nativeType.equals("text")
                ? OceanbaseMappingFixtures.text("v", "text", "utf8mb4_general_ci")
                : OceanbaseMappingFixtures.column("v", nativeType);
        var mapping = MAPPINGS.map(POSTGRESQL, source, false);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(mapping));
        if (mapping.definition() != null) assertEquals(check.equals("-") ? null : check, checkOf(mapping.definition()));
    }

    @ParameterizedTest(name = "{0} {1} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "char(10); utf8mb4_general_ci; LOSSY|character(10)|COLLATION_EQUALITY_CHANGED",
            "char(5); utf8mb4_bin; EXACT|character(5) COLLATE \"C\"|",
            "varchar(64); utf8mb4_general_ci; LOSSY|character varying(64)|COLLATION_EQUALITY_CHANGED",
            "varchar(5); utf8mb4_bin; LOSSY|character varying(5) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED"})
    void oceanbaseCharacterToPostgresGolden(String nativeType, String collation, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OceanbaseMappingFixtures.text("v", nativeType, collation), false)));
    }

    @ParameterizedTest(name = "{0} -> OCEANBASE")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|",
            "integer; EXACT|int|",
            "bigint; EXACT|bigint|",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(10,2); UNSUPPORTED|-|DECIMAL_NAN_UNVERIFIED",
            "numeric(66,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; UNSUPPORTED|-|FLOAT32_NON_FINITE_UNVERIFIED",
            "double precision; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED,NON_FINITE_NOT_REPRESENTABLE",
            "boolean; EXACT|tinyint(1)|",
            "character varying(40); LOSSY|varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|PAD_SPACE_EQUALITY_CHANGED",
            "character(10); EXACT|char(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|",
            "character varying(65); UNSUPPORTED|-|LENGTH_CAP_UNVERIFIED",
            "text; UNSUPPORTED|-|TYPE_UNMAPPED",
            "bytea; EXACT|blob|",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(6) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_VALUE_UNPROBED",
            "timestamp(6) without time zone; LOSSY|datetime(6)|DATE_RANGE_NOT_REPRESENTABLE,ZERO_DATETIME_READ_AS_0002_11_30",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED"})
    void postgresSourceToOceanbaseGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(OCEANBASE,
                LogicalTypeMappingTest.pg("v", nativeType, pgJdbc(nativeType)), false)));
    }

    @ParameterizedTest(name = "{0} -> MYSQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint(4); EXACT|tinyint|",
            "int(10) unsigned; EXACT|int unsigned|",
            "decimal(10,2); EXACT|decimal(10,2)|",
            "float; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED",
            "double; EXACT|double|",
            "binary(4); EXACT|binary(4)|",
            "blob; EXACT|blob|",
            "tinyint(1); LOSSY|tinyint(1)|TINYINT1_NONZERO_READ_AS_TRUE",
            "date; LOSSY|date|ZERO_DATE_READ_AS_NULL",
            "datetime(6); LOSSY|datetime(6)|ZERO_DATETIME_READ_AS_0002_11_30",
            "varchar(64); UNSUPPORTED|-|CHARSET_UNVERIFIED",
            "text; UNSUPPORTED|-|TYPE_UNMAPPED"})
    void oceanbaseSourceToMysqlGolden(String nativeType, String expected) {
        var source = nativeType.equals("varchar(64)")
                ? OceanbaseMappingFixtures.text("v", nativeType, "utf8mb4_general_ci")
                : nativeType.equals("text")
                ? OceanbaseMappingFixtures.text("v", "text", "utf8mb4_general_ci")
                : OceanbaseMappingFixtures.column("v", nativeType);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(MYSQL, source, false)));
    }

    @Test void probedGapsStayVisibleWithoutChangingFidelity() {
        var medium = MAPPINGS.map(POSTGRESQL, OceanbaseMappingFixtures.column("v", "mediumint(9)"), false);
        assertEquals("EXACT|integer|", PgFamilyMappingFixtures.outcome(medium));
        assertTrue(medium.issues().stream().anyMatch(issue -> issue.code().equals("INTEGER_BOUNDS_PARTIALLY_PROBED")));
        var tiny = MAPPINGS.map(POSTGRESQL, OceanbaseMappingFixtures.column("v", "tinyint(4)"), false);
        assertTrue(tiny.issues().stream().noneMatch(issue -> issue.code().equals("INTEGER_BOUNDS_PARTIALLY_PROBED")));
        var date = MAPPINGS.map(POSTGRESQL, OceanbaseMappingFixtures.column("v", "date"), false);
        assertTrue(date.issues().stream().anyMatch(issue -> issue.code().equals("DATE_YEAR_BOUNDS_UNPROBED")));
        var binary = MAPPINGS.map(POSTGRESQL, OceanbaseMappingFixtures.column("v", "binary(4)"), false);
        assertTrue(binary.issues().stream().anyMatch(issue -> issue.code().equals("BINARY_PAYLOAD_UNPROBED")));
        var bin = MAPPINGS.map(POSTGRESQL, OceanbaseMappingFixtures.text("v", "varchar(5)", "utf8mb4_bin"), false);
        assertTrue(bin.issues().stream().anyMatch(issue -> issue.code().equals("BIN_CASE_FOLD_UNPROBED")));
    }

    @Test void driverDoublePrecisionMustStayAheadOfTheCatalog() {
        var column = OceanbaseMappingFixtures.driverPrecision(OceanbaseMappingFixtures.column("v", "double"), 22);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column, false)));
    }

    @Test void mysqlShapedProvenanceIsRejected() {
        var column = OceanbaseMappingFixtures.provenance(OceanbaseMappingFixtures.column("v", "int(11)"),
                new MetadataProvenance(OCEANBASE, "MySQL", OceanbaseMappingFixtures.VERSION, "OCEANBASE_CATALOG_V1", Instant.EPOCH));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column, false)));
        var oracleMethod = OceanbaseMappingFixtures.provenance(OceanbaseMappingFixtures.column("v", "int(11)"),
                new MetadataProvenance(OCEANBASE, "OceanBase", OceanbaseMappingFixtures.VERSION, "OCEANBASE_ORACLE_CATALOG_V1", Instant.EPOCH));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, oracleMethod, false)));
    }

    @Test void characterKeysAndUnprobedBinaryLengthsStayUnsupported() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OceanbaseMappingFixtures.text("k", "varchar(64)", "utf8mb4_general_ci"), true)));
        assertEquals("EXACT|int|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(OCEANBASE,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        var mapper = new OceanbaseLogicalTypeMapper();
        var longer = mapper.toNative(new LogicalColumn("v", new BinaryType(StorageKind.FIXED, 5L, null), true, false, POSTGRESQL, "bytea"));
        assertEquals("LENGTH_CAP_UNVERIFIED", longer.issues().get(0).code());
    }

    private static String checkOf(String definition) {
        int start = definition.indexOf(" CHECK (");
        return start < 0 ? null : definition.substring(start + 8, definition.length() - 1);
    }

    private static int pgJdbc(String type) {
        String base = type.replaceAll("\\(.*", "");
        return switch (base) {
            case "smallint" -> Types.SMALLINT;
            case "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "numeric" -> Types.NUMERIC;
            case "real" -> Types.REAL;
            case "double precision" -> Types.DOUBLE;
            case "boolean" -> Types.BIT;
            case "character varying", "text" -> Types.VARCHAR;
            case "character" -> Types.CHAR;
            case "bytea" -> Types.BINARY;
            case "date" -> Types.DATE;
            case "time" -> Types.TIME;
            case "timestamp" -> Types.TIMESTAMP;
            default -> type.startsWith("timestamp") ? Types.TIMESTAMP : type.startsWith("time") ? Types.TIME : Types.OTHER;
        };
    }
}
