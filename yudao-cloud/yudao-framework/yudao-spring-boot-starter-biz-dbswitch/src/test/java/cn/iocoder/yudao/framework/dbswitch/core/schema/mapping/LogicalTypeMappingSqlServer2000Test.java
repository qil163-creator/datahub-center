package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.BinaryType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.BooleanType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.CharacterType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.Comparison;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.DecimalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.IntegerType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.LengthUnit;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.StorageKind;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.TimeZoneSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType.TimestampType;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerFamilyLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.Sqlserver2000LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER2000;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL Server 2000 8.00.760 (JDBC Driver 3.0.1301.101) logical type mapping.
 * Golden values come from that probe. No connection is opened here.
 */
@Timeout(60)
class LogicalTypeMappingSqlServer2000Test {
    private static final String COLLATION = Sqlserver2000LogicalTypeMapper.COLLATION;
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheSqlServer2000MapperWithoutReplacingSqlServer2022() {
        var mapper = REGISTRY.typeMapper(SQLSERVER2000).orElseThrow();
        assertInstanceOf(Sqlserver2000LogicalTypeMapper.class, mapper);
        assertEquals(SQLSERVER2000, mapper.product());
        assertEquals(SqlServerFamilyLogicalTypeMapper.Flavor.SQLSERVER,
                ((SqlServerFamilyLogicalTypeMapper) REGISTRY.typeMapper(SQLSERVER).orElseThrow()).flavor());
        assertTrue(MAPPINGS.supports(SQLSERVER2000, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, SQLSERVER2000));
        assertTrue(!MAPPINGS.supports(SQLSERVER2000, SQLSERVER2000));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "bit; BOOLEAN",
            "tinyint; INTEGER(8,UNSIGNED)",
            "smallint; INTEGER(16,SIGNED)",
            "int; INTEGER(32,SIGNED)",
            "bigint; INTEGER(64,SIGNED)",
            "decimal; DECIMAL(18,4)",
            "decimal(18,4); DECIMAL(18,4)",
            "money; DECIMAL(19,4)",
            "varchar; CHARACTER(VARYING,40,CHARACTERS,SQL_Latin1_General_CP1_CI_AS,NAMED:SQL_Latin1_General_CP1_CI_AS)",
            "varchar(40); CHARACTER(VARYING,40,CHARACTERS,SQL_Latin1_General_CP1_CI_AS,NAMED:SQL_Latin1_General_CP1_CI_AS)",
            "nvarchar; CHARACTER(VARYING,40,CHARACTERS,UNICODE,NAMED:SQL_Latin1_General_CP1_CI_AS)",
            "datetime; TIMESTAMP(3,LOCAL,1753..9999)",
            "smalldatetime; TIMESTAMP(0,LOCAL,1900..2079)",
            "text; CHARACTER(LARGE,MAX_OCTETS=2147483647,SQL_Latin1_General_CP1_CI_AS,NAMED:SQL_Latin1_General_CP1_CI_AS)",
            "image; BINARY(LARGE,MAX_OCTETS=2147483647)"})
    void probedTypesBecomeLogicalDomains(String kind, String canonical) {
        var result = mapper().toLogical(column(kind));
        assertEquals(TypeFidelity.EXACT, result.fidelity(), result.toString());
        assertEquals(canonical, result.value().canonical());
        if ("smalldatetime".equals(kind)) assertTrue(result.issues().stream().anyMatch(issue ->
                issue.severity() == TypeMappingIssue.Severity.NOTE && "SMALLDATETIME_ENDPOINT".equals(issue.code())));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "bit; EXACT|boolean|",
            "tinyint; EXACT|smallint|",
            "smallint; EXACT|smallint|",
            "int; EXACT|integer|",
            "bigint; EXACT|bigint|",
            "decimal; EXACT|numeric(18,4)|",
            "money; EXACT|numeric(19,4)|",
            "varchar; LOSSY|character varying(40)|COLLATION_EQUALITY_CHANGED",
            "nvarchar; LOSSY|character varying(40)|COLLATION_EQUALITY_CHANGED",
            "datetime; EXACT|timestamp(3) without time zone|",
            "smalldatetime; EXACT|timestamp(0) without time zone|",
            "text; LOSSY|text|COLLATION_EQUALITY_CHANGED",
            "image; EXACT|bytea|"})
    void sqlServer2000SourceToPostgresGolden(String kind, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column(kind), false)));
    }

    @Test void characterKeysAndUnprobedTypesStayClosed() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column("varchar"), true)));
        assertEquals("UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, SqlServer2000MappingFixtures.datetime2("v"), false)));
        assertEquals("UNSUPPORTED|-|TYPE_UNMAPPED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlServer2000MappingFixtures.decimal("v", 39, 0), false)));
        assertEquals("UNSUPPORTED|-|TYPE_UNMAPPED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlServer2000MappingFixtures.varchar("v", 8001), false)));
        assertEquals("UNSUPPORTED|-|TYPE_UNMAPPED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlServer2000MappingFixtures.nvarchar("v", 4001), false)));
    }

    @Test void driverShapeAndProvenanceFailClosed() {
        var signedTinyint = SqlServer2000MappingFixtures.tinyint("v");
        signedTinyint.setSigned(true);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, signedTinyint, false)));
        var wrongScale = SqlServer2000MappingFixtures.decimal("v", 18, 4);
        wrongScale.setScaleSize(5);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongScale, false)));
        var missingLength = SqlServer2000MappingFixtures.withFacts(SqlServer2000MappingFixtures.varchar("v", 40),
                facts -> new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                        facts.nullable(), facts.numericPrecision(), facts.numericScale(), MetadataValue.unknown(),
                        facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, missingLength, false)));
        var otherCollation = SqlServer2000MappingFixtures.withFacts(SqlServer2000MappingFixtures.varchar("v", 40),
                facts -> new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), facts.defaultExpression(),
                        facts.nullable(), facts.numericPrecision(), facts.numericScale(), facts.characterLength(),
                        facts.identity(), facts.generatedExpression(), facts.charset(), MetadataValue.value("Latin1_General_CI_AS"),
                        facts.sequence()));
        assertEquals("UNSUPPORTED|-|COLLATION_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, otherCollation, false)));
        var otherVersion = SqlServer2000MappingFixtures.withFacts(SqlServer2000MappingFixtures.integer("v"),
                facts -> new ColumnMetadataEvidence(new MetadataProvenance(SQLSERVER2000, "Microsoft SQL Server", "16.00.4295",
                        Sqlserver2000LogicalTypeMapper.METHOD, Instant.EPOCH), facts.nativeType(), facts.defaultExpression(),
                        facts.nullable(), facts.numericPrecision(), facts.numericScale(), facts.characterLength(),
                        facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, otherVersion, false)));
    }

    @Test void nativeTargetsUseOnlyProbedSqlServer2000Types() {
        var mapper = mapper();
        assertEquals("tinyint", nativeSql(mapper, new IntegerType(8, true), false));
        assertEquals("smallint", nativeSql(mapper, new IntegerType(16, false), false));
        assertEquals("int", nativeSql(mapper, new IntegerType(32, false), false));
        assertEquals("bigint", nativeSql(mapper, new IntegerType(64, false), false));
        assertEquals("bit", nativeSql(mapper, new BooleanType(), false));
        assertEquals("decimal(19,4)", nativeSql(mapper, new DecimalType(19, 4, false, false), false));
        assertEquals("varchar(40)", nativeSql(mapper, character(StorageKind.VARYING, 40L, COLLATION), false));
        assertEquals("nvarchar(40)", nativeSql(mapper, character(StorageKind.VARYING, 40L, "UNICODE"), false));
        assertEquals("text", nativeSql(mapper, new CharacterType(StorageKind.LARGE, null, LengthUnit.CHARACTERS,
                2147483647L, COLLATION, Comparison.NAMED, COLLATION, false), false));
        assertEquals("image", nativeSql(mapper, new BinaryType(StorageKind.LARGE, null, 2147483647L), false));
        assertEquals("datetime", nativeSql(mapper, new TimestampType(3, TimeZoneSemantics.LOCAL, 1753, 9999, false), false));
        var smalldatetime = mapper.toNative(logical(new TimestampType(0, TimeZoneSemantics.LOCAL, 1900, 2079, false), false));
        assertEquals("smalldatetime", smalldatetime.value().typeSql());
        assertTrue(smalldatetime.issues().stream().anyMatch(issue -> "SMALLDATETIME_ENDPOINT".equals(issue.code())));
        assertEquals(TypeFidelity.UNSUPPORTED, mapper.toNative(logical(new IntegerType(8, false), false)).fidelity());
        assertEquals(TypeFidelity.UNSUPPORTED, mapper.toNative(logical(new DecimalType(19, 4, false, true), false)).fidelity());
        assertEquals("NAN_DOMAIN_UNPROVEN", mapper.toNative(logical(new DecimalType(19, 4, false, true), false)).issues().get(0).code());
        assertEquals(TypeFidelity.UNSUPPORTED, mapper.toNative(logical(new TimestampType(7, TimeZoneSemantics.LOCAL, 1, 9999, false), false)).fidelity());
        assertEquals("TEXT_KEY_EQUALITY_UNPROVEN", mapper.toNative(logical(character(StorageKind.VARYING, 40L, COLLATION), true)).issues().get(0).code());
    }

    @Test void plannerQuotesASqlServer2000Target() {
        var mapping = MAPPINGS.map(SQLSERVER2000, LogicalTypeMappingTest.pg("v", "integer", Types.INTEGER), false);
        assertEquals("[v] int", mapping.definition());
    }

    private static Sqlserver2000LogicalTypeMapper mapper() {
        return (Sqlserver2000LogicalTypeMapper) REGISTRY.typeMapper(SQLSERVER2000).orElseThrow();
    }

    private static ColumnDescription column(String kind) {
        return switch (kind) {
            case "bit" -> SqlServer2000MappingFixtures.bit("v");
            case "tinyint" -> SqlServer2000MappingFixtures.tinyint("v");
            case "smallint" -> SqlServer2000MappingFixtures.smallint("v");
            case "int" -> SqlServer2000MappingFixtures.integer("v");
            case "bigint" -> SqlServer2000MappingFixtures.bigint("v");
            case "decimal" -> SqlServer2000MappingFixtures.decimal("v", 18, 4);
            case "decimal(18,4)" -> SqlServer2000MappingFixtures.decimalSized("v", 18, 4);
            case "money" -> SqlServer2000MappingFixtures.money("v");
            case "varchar" -> SqlServer2000MappingFixtures.varchar("v", 40);
            case "varchar(40)" -> SqlServer2000MappingFixtures.varcharSized("v", 40);
            case "nvarchar" -> SqlServer2000MappingFixtures.nvarchar("v", 40);
            case "datetime" -> SqlServer2000MappingFixtures.datetime("v");
            case "smalldatetime" -> SqlServer2000MappingFixtures.smalldatetime("v");
            case "text" -> SqlServer2000MappingFixtures.text("v");
            case "image" -> SqlServer2000MappingFixtures.image("v");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static CharacterType character(StorageKind kind, Long length, String repertoire) {
        return new CharacterType(kind, length, LengthUnit.CHARACTERS, null, repertoire, Comparison.NAMED, COLLATION, false);
    }

    private static String nativeSql(Sqlserver2000LogicalTypeMapper mapper, LogicalType type, boolean key) {
        var result = mapper.toNative(logical(type, key));
        assertEquals(TypeFidelity.EXACT, result.fidelity(), result.toString());
        return result.value().typeSql();
    }

    private static LogicalColumn logical(LogicalType type, boolean key) {
        return new LogicalColumn("v", type, true, key, POSTGRESQL, type.canonical());
    }
}
