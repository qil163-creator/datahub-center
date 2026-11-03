package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 新目录策略均使用真实 PostgreSQL 验证统计、错误明细与正常明细。 */
@Testcontainers(disabledWithoutDocker = true)
class QdataCompleteAuditPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AuditSqlRegistry REGISTRY = new AuditSqlRegistry();

    @Test
    void numericRelationsPreserveExactDecimalsAndKnownFailuresBesideIgnoredNull() throws Exception {
        try (Connection connection = fixture("a numeric,b numeric,c numeric", """
                (1,true,'inside',9007199254740993.0000000000000000001,9007199254740993.0000000000000000002,9007199254740993.0000000000000000003),
                (2,true,'inside',2,1,3),(3,true,'inside',NULL,1,2),(4,true,'inside',NULL,3,2),
                (5,true,'inside',NULL,NULL,NULL),(80,true,'outside',3,2,1),(90,false,'inside',3,2,1)
                """)) {
            var conditions = List.of(Map.of("leftField", "a", "rightField", "b", "operator", "<="),
                    Map.of("leftField", "b", "rightField", "c", "operator", "<"));
            AuditRuleContext context = context(List.of("a", "b", "c"), Map.of("conditions", conditions));
            population(connection, "NUMERIC_RELATION_VALIDATION", context, 5, List.of(2, 3, 4, 5));
            context.setConfig(Map.of("conditions", conditions, "ignoreNullValue", true));
            population(connection, "NUMERIC_RELATION_VALIDATION", context, 5, List.of(2, 4));
        }
    }

    @Test
    void conditionRequiredDoesNotConfuseInactiveNullTriggersWithMissingRequiredValues() throws Exception {
        try (Connection connection = fixture("state text,name text,code text", """
                (1,true,'inside','ACTIVE','name','0'),(2,true,'inside','ACTIVE',NULL,'code'),
                (3,true,'inside','ACTIVE','   ','code'),(4,true,'inside','OTHER',NULL,NULL),
                (5,true,'inside',NULL,NULL,NULL),(6,true,'inside','ACTIVE','','code'),
                (80,true,'outside','ACTIVE',NULL,NULL),(90,false,'inside','ACTIVE',NULL,NULL)
                """)) {
            AuditRuleContext context = context(List.of("state", "name", "code"), Map.of("conditionColumn", "state", "conditionValues", List.of("ACTIVE"), "requiredColumns", List.of("name", "code")));
            population(connection, "CONDITIONAL_REQUIRED_VALIDATION", context, 6, List.of(2, 3, 6));
            context.setConfig(Map.of("conditionColumn", "state", "conditionValues", List.of("ACTIVE"), "requiredColumns", List.of("name", "code"), "blankAsNull", false, "ignoreNullValue", true));
            population(connection, "CONDITIONAL_REQUIRED_VALIDATION", context, 6, List.of(2));
        }
    }

    @Test
    void recordCompletenessCountsFalsyValuesAndTrimsOnlyWhenConfigured() throws Exception {
        try (Connection connection = fixture("name text,n numeric,flag boolean", """
                (1,true,'inside',NULL,NULL,NULL),(2,true,'inside','   ',NULL,NULL),
                (3,true,'inside',NULL,0,NULL),(4,true,'inside',NULL,NULL,false),
                (5,true,'inside','',NULL,NULL),(6,true,'inside','x',NULL,NULL),
                (80,true,'outside',NULL,NULL,NULL),(90,false,'inside',NULL,NULL,NULL)
                """)) {
            AuditRuleContext context = context(List.of("name", "n", "flag"), Map.of());
            population(connection, "RECORD_COMPLETENESS_VALIDATION", context, 6, List.of(1, 2, 5));
            context.setConfig(Map.of("blankAsNull", false));
            population(connection, "RECORD_COMPLETENESS_VALIDATION", context, 6, List.of(1));
        }
    }

    @Test
    void sensitiveMaskValidatesAllPresetStructuresAndLiteralMaskCharacters() throws Exception {
        try (Connection connection = fixture("value text", """
                (1,true,'inside','138****1234'),(2,true,'inside','13800131234'),
                (3,true,'inside','138***1234'),(4,true,'inside',NULL),(5,true,'inside','138####1234'),
                (6,true,'inside','110101********123X'),(7,true,'inside','********1234'),
                (8,true,'inside','AB-123'),(80,true,'outside','clear'),(90,false,'inside','clear')
                """)) {
            AuditRuleContext context = context(List.of("value"), Map.of("maskType", "PHONE"));
            population(connection, "SENSITIVE_MASK_VALIDATION", context, 8, List.of(2, 3, 4, 5, 6, 7, 8));
            context.setConfig(Map.of("maskType", "PHONE", "maskChar", "#", "ignoreNullValue", true));
            population(connection, "SENSITIVE_MASK_VALIDATION", context, 8, List.of(1, 2, 3, 6, 7, 8));
            context.setConfig(Map.of("maskType", "ID_CARD"));
            population(connection, "SENSITIVE_MASK_VALIDATION", context, 8, List.of(1, 2, 3, 4, 5, 7, 8));
            context.setConfig(Map.of("maskType", "BANK_CARD"));
            population(connection, "SENSITIVE_MASK_VALIDATION", context, 8, List.of(1, 2, 3, 4, 5, 6, 8));
            context.setConfig(Map.of("maskType", "CUSTOM", "regex", "^[A-Z]{2}-[0-9]{3}$"));
            population(connection, "SENSITIVE_MASK_VALIDATION", context, 8, List.of(1, 2, 3, 4, 5, 6, 7));
        }
    }

    @Test
    void outlierMethodsUseExactBoundsAndScopeBeforeQuartiles() throws Exception {
        try (Connection connection = fixture("value numeric", """
                (1,true,'inside',1),(2,true,'inside',2),(3,true,'inside',3),(4,true,'inside',4),
                (5,true,'inside',100),(6,true,'inside',NULL),(80,true,'outside',1000000),(90,false,'inside',1000000)
                """)) {
            AuditRuleContext context = context(List.of("value"), Map.of("method", "IQR"));
            population(connection, "NUMERIC_OUTLIER_VALIDATION", context, 6, List.of(5, 6));
            context.setConfig(Map.of("method", "IQR", "ignoreNullValue", true));
            population(connection, "NUMERIC_OUTLIER_VALIDATION", context, 6, List.of(5));
            context.setConfig(Map.of("method", "IQR", "minSamples", 6));
            population(connection, "NUMERIC_OUTLIER_VALIDATION", context, 6, List.of(6));
            context.setConfig(Map.of("method", "RANGE", "minValue", "1", "maxValue", "4", "includeMin", false, "includeMax", false));
            population(connection, "NUMERIC_OUTLIER_VALIDATION", context, 6, List.of(1, 4, 5, 6));
            context.setConfig(Map.of("method", "INVALID_VALUES", "values", List.of("2.000", "100"), "ignoreNullValue", true));
            population(connection, "NUMERIC_OUTLIER_VALIDATION", context, 6, List.of(2, 5));
        }
    }

    @Test
    void quartileAliasesCannotShadowBusinessColumnNames() throws Exception {
        for (String column : List.of("q1", "q3", "n", "\"sample value\"")) {
            try (Connection connection = fixture(column + " numeric", "(1,true,'inside',1),(2,true,'inside',2),(3,true,'inside',3),(4,true,'inside',4),(5,true,'inside',100)")) {
                population(connection, "NUMERIC_OUTLIER_VALIDATION", context(List.of(column), Map.of("method", "IQR")), 5, List.of(5));
            }
        }
    }

    @Test
    void businessStateAssertionsRequireAllConditionsWithExactDecimalAndNullSemantics() throws Exception {
        try (Connection connection = fixture("state text,amount numeric,note text,reason text", """
                (1,true,'inside','PAID',9007199254740993.0000000000000000002,'OK',NULL),
                (2,true,'inside','PAID',9007199254740993.0000000000000000001,'OK',NULL),
                (3,true,'inside','PAID',9007199254740993.0000000000000000002,NULL,NULL),
                (4,true,'inside','PAID',9007199254740993.0000000000000000002,'OK','bad'),
                (5,true,'inside','OTHER',NULL,NULL,'anything'),(6,true,'inside',NULL,NULL,NULL,NULL),
                (80,true,'outside','PAID',0,NULL,'bad'),(90,false,'inside','PAID',0,NULL,'bad')
                """)) {
            var assertions = List.of(Map.of("column", "amount", "operator", "GT", "valueType", "DECIMAL", "value", "9007199254740993.0000000000000000001"),
                    Map.of("column", "note", "operator", "IN", "values", List.of("OK", "O'Reilly")), Map.of("column", "reason", "operator", "IS_NULL"));
            AuditRuleContext context = context(List.of("state", "amount", "note", "reason"), Map.of("conditionColumn", "state", "conditionValues", List.of("PAID"), "assertions", assertions));
            population(connection, "STATE_CONSISTENCY_VALIDATION", context, 6, List.of(2, 3, 4));
        }
    }

    @Test
    void dateValidationRejectsImpossibleDatesEvenWhenLegacyRegexAllowsThem() throws Exception {
        try (Connection connection = fixture("value text", """
                (1,true,'inside','2000-02-29'),(2,true,'inside','1900-02-29'),(3,true,'inside','2024-02-29'),
                (4,true,'inside','2023-02-29'),(5,true,'inside','2026-04-31'),(6,true,'inside','2026-04-30'),
                (7,true,'inside','0000-01-01'),(8,true,'inside','0001-01-01'),(9,true,'inside',NULL),
                (10,true,'inside','1970-01-01'),(80,true,'outside','bad'),(90,false,'inside','bad')
                """)) {
            AuditRuleContext context = context(List.of("value"), Map.of("pattern", "yyyy-MM-dd", "regex", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", "invalidValues", List.of("1970-01-01")));
            population(connection, "DATE_FORMAT_VALIDATION", context, 10, List.of(2, 4, 5, 7, 9, 10));
            context.setConfig(Map.of("pattern", "yyyy-MM-dd", "ignoreNullValue", true));
            population(connection, "DATE_FORMAT_VALIDATION", context, 10, List.of(2, 4, 5, 7));
        }
    }

    @Test
    void everySupportedDatePatternChecksCalendarAndClock() throws Exception {
        for (String pattern : List.of("yyyy/MM/dd", "yyyyMMdd", "HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")) {
            String good = switch (pattern) {
                case "yyyy/MM/dd" -> "2024/02/29"; case "yyyyMMdd" -> "20240229"; case "HH:mm:ss" -> "23:59:59";
                case "yyyy-MM-dd HH:mm:ss" -> "2024-02-29 23:59:59"; case "yyyy/MM/dd HH:mm:ss" -> "2024/02/29 23:59:59";
                default -> "2024-02-29T23:59:59";
            };
            String bad = good.replace("2024", "1900").replace("23:59:59", "24:00:00");
            try (Connection connection = fixture("value text", "(1,true,'inside','" + good + "'),(2,true,'inside','" + bad + "')")) {
                population(connection, "DATE_FORMAT_VALIDATION", context(List.of("value"), Map.of("pattern", pattern)), 2, List.of(2));
            }
        }
    }

    @Test
    void crossTableComparesValuesWithinOneReferenceRowAndNullReferenceDoesNotHideOrphans() throws Exception {
        try (Connection connection = fixture("ref_id integer,name text,amount numeric", """
                (1,true,'inside',10,'same',1),(2,true,'inside',20,'wrong',1),(3,true,'inside',30,NULL,NULL),
                (4,true,'inside',40,'split',2),(5,true,'inside',999,'absent',1),(6,true,'inside',NULL,'none',1),
                (7,true,'inside',50,'duplicate',3),(80,true,'outside',999,'absent',1),(90,false,'inside',999,'absent',1)
                """)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE reference_fixture(ref_id integer,name text,amount numeric)");
                statement.execute("INSERT INTO reference_fixture VALUES (10,'same',1),(20,'other',1),(30,NULL,NULL),(40,'split',1),(40,'other',2),(50,'duplicate',1),(50,'duplicate',3),(NULL,'none',1)");
            }
            AuditRuleContext context = context(List.of("ref_id", "name", "amount"), Map.of("refTable", "reference_fixture", "refColumn", "ref_id", "leftColumn", "ref_id", "comparisons", List.of(Map.of("leftColumn", "name", "rightColumn", "name"), Map.of("leftColumn", "amount", "rightColumn", "amount"))));
            population(connection, "CROSS_TABLE_CONSISTENCY", context, 7, List.of(2, 4, 5, 6));
            context.setConfig(Map.of("refTable", "reference_fixture", "refColumn", "ref_id", "ignoreNullValue", true));
            context.setColumn("ref_id"); context.setColumns(List.of());
            population(connection, "FOREIGN_KEY_VALIDATION", context, 7, List.of(5));
        }
    }

    @Test
    void quotedReferenceAndSourceColumnsWithSpacesRemainUnambiguous() throws Exception {
        try (Connection connection = fixture("\"join key\" integer,\"business value\" text", "(1,true,'inside',10,'same'),(2,true,'inside',10,'different')")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE \"reference table\"(\"join key\" integer,\"business value\" text)");
                statement.execute("INSERT INTO \"reference table\" VALUES (10,'same')");
            }
            AuditRuleContext context = context(List.of("\"join key\"", "\"business value\""), Map.of("refTable", "\"reference table\"", "refColumn", "\"join key\"", "leftColumn", "\"join key\"", "comparisons", List.of(Map.of("leftColumn", "\"business value\"", "rightColumn", "\"business value\""))));
            population(connection, "CROSS_TABLE_CONSISTENCY", context, 2, List.of(2));
        }
    }

    @Test
    void requiredValuesDistinguishNullEmptyAndExplicitlyAllowedBlank() throws Exception {
        try (Connection connection = fixture("value text", "(1,true,'inside',NULL),(2,true,'inside',''),(3,true,'inside',' '),(4,true,'inside','0'),(80,true,'outside',NULL),(90,false,'inside',NULL)")) {
            AuditRuleContext context = context(List.of("value"), Map.of("allowBlank", false));
            population(connection, "NOT_NULL_VALIDATION", context, 4, List.of(1, 2));
            context.setConfig(Map.of("allowBlank", true));
            population(connection, "NOT_NULL_VALIDATION", context, 4, List.of(1));
        }
    }

    @Test
    void placeholderValuesHonorCaseTrimEmptyStringAndNullConfiguration() throws Exception {
        try (Connection connection = fixture("value text", "(1,true,'inside','N/A'),(2,true,'inside','n/a'),(3,true,'inside',' N/A '),(4,true,'inside',''),(5,true,'inside',NULL),(6,true,'inside','value'),(80,true,'outside','N/A'),(90,false,'inside','N/A')")) {
            AuditRuleContext context = context(List.of("value"), Map.of("values", List.of("N/A", "")));
            population(connection, "PLACEHOLDER_VALIDATION", context, 6, List.of(1, 2, 4, 5));
            context.setConfig(Map.of("values", List.of("N/A", ""), "trim", true, "ignoreNullValue", true));
            population(connection, "PLACEHOLDER_VALIDATION", context, 6, List.of(1, 2, 3, 4));
            context.setConfig(Map.of("values", List.of("N/A"), "ignoreCase", false));
            population(connection, "PLACEHOLDER_VALIDATION", context, 6, List.of(1, 5));
        }
    }

    @Test
    void mutualExclusionCountsOnlySelectedTruthPredicates() throws Exception {
        try (Connection connection = fixture("a text,b text", "(1,true,'inside',NULL,NULL),(2,true,'inside','yes',NULL),(3,true,'inside','yes','Y'),(4,true,'inside','no','N'),(5,true,'inside','',''),(80,true,'outside','yes','Y'),(90,false,'inside','yes','Y')")) {
            AuditRuleContext context = context(List.of("a", "b"), Map.of("condition", "NOT_NULL"));
            population(connection, "MUTUAL_EXCLUSION_VALIDATION", context, 5, List.of(3, 4, 5));
            context.setConfig(Map.of("condition", "IS_TRUE", "trueValues", List.of("yes", "Y")));
            population(connection, "MUTUAL_EXCLUSION_VALIDATION", context, 5, List.of(3));
        }
    }

    @Test
    void modelComparisonUsesRealDatabaseMetadataAndCountsColumnsRatherThanRows() throws Exception {
        try (Connection connection = fixture("value varchar(8)", "(1,true,'inside','ok'),(2,true,'inside','ok')")) {
            AuditRuleContext context = context(List.of(), Map.of("expectedColumns", List.of(
                    Map.of("name", "id", "type", "int4", "nullable", true), Map.of("name", "active", "type", "bool", "nullable", true),
                    Map.of("name", "population", "type", "text", "nullable", true), Map.of("name", "value", "type", "varchar", "length", 9), Map.of("name", "missing", "type", "text"))));
            context.setColumnSnapshots(readSnapshots(connection));
            AuditSqlGenerator generator = REGISTRY.getGenerator("MODEL_STRUCTURE_VALIDATION");
            assertEquals(AuditSqlGenerator.ExecutionMode.METADATA, generator.getExecutionMode());
            counts(connection, generator.buildCountSql(context), 5, 2);
            try (var statement = connection.createStatement(); var result = statement.executeQuery(generator.buildErrorDataSql(context, 10))) {
                List<String> columns = new ArrayList<>();
                while (result.next()) columns.add(result.getString("columnName"));
                assertEquals(List.of("value", "missing"), columns);
            }
            context.setConfig(Map.of("expectedColumns", List.of(Map.of("name", "id", "type", "int4"))));
            counts(connection, generator.buildCountSql(context), 4, 3);
            context.setConfig(Map.of("expectedColumns", List.of(Map.of("name", "id", "type", "int4")), "allowExtraColumns", true));
            counts(connection, generator.buildCountSql(context), 1, 0);
        }
    }

    @Test
    void columnMetadataCompletenessChecksFalseAndZeroAsKnownMetadata() throws Exception {
        try (Connection connection = fixture("value varchar(8)", "(1,true,'inside','ok')")) {
            try (var statement = connection.createStatement()) { statement.execute("COMMENT ON COLUMN qdata_complete_fixture.value IS '业务字段'"); }
            AuditRuleContext context = context(List.of(), Map.of());
            context.setColumnSnapshots(readSnapshots(connection));
            AuditSqlGenerator generator = REGISTRY.getGenerator("COLUMN_METADATA_COMPLETENESS");
            counts(connection, generator.buildCountSql(context), 4, 3);
            context.setColumnSnapshots(List.of(new AuditRuleContext.ColumnSnapshot("amount", "numeric", false, 0, 10, 0, "金额")));
            context.setConfig(Map.of("requiredProperties", List.of("name", "type", "comment", "nullable", "length", "precision", "scale")));
            counts(connection, generator.buildCountSql(context), 1, 0);
            context.setColumnSnapshots(List.of());
            counts(connection, generator.buildCountSql(context), 0, 0);
        }
    }

    private static List<AuditRuleContext.ColumnSnapshot> readSnapshots(Connection connection) throws Exception {
        List<AuditRuleContext.ColumnSnapshot> columns = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT a.attname,t.typname,NOT a.attnotnull AS nullable,
                CASE WHEN t.typname='varchar' THEN a.atttypmod-4 ELSE NULL END AS length,
                col_description(a.attrelid,a.attnum) AS comment
                FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid
                WHERE a.attrelid='qdata_complete_fixture'::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum
                """)) {
            while (result.next()) columns.add(new AuditRuleContext.ColumnSnapshot(result.getString("attname"), result.getString("typname"), result.getBoolean("nullable"),
                    (Integer) result.getObject("length"), null, null, result.getString("comment")));
        }
        return columns;
    }
    private static Connection fixture(String columns, String rows) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE qdata_complete_fixture(id integer,active boolean,population text," + columns + ")");
            statement.execute("INSERT INTO qdata_complete_fixture VALUES " + rows);
        } catch (Exception ex) { connection.close(); throw ex; }
        return connection;
    }
    private static AuditRuleContext context(List<String> columns, Map<String, Object> config) {
        AuditRuleContext context = new AuditRuleContext(DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("qdata_complete_fixture");
        context.setColumns(columns); if (!columns.isEmpty()) context.setColumn(columns.get(0));
        context.setConfig(config); context.setWhereClause("active = true OR id = 80"); context.addTextEqualsScope("population", "inside");
        return context;
    }
    private static void population(Connection connection, String strategy, AuditRuleContext context, int total, List<Integer> errors) throws Exception {
        AuditSqlGenerator generator = REGISTRY.getGenerator(strategy);
        counts(connection, generator.buildCountSql(context), total, errors.size());
        assertEquals(errors, ids(connection, generator.buildErrorDataSql(context, 100)), strategy);
        List<Integer> normals = new ArrayList<>(); for (int id = 1; id <= total; id++) if (!errors.contains(id)) normals.add(id);
        assertEquals(normals, ids(connection, generator.buildNormalDataSql(context, 100)), strategy);
    }
    private static void counts(Connection connection, String sql, long total, long errors) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql); assertEquals(total, result.getLong("totalCount"), sql); assertEquals(errors, result.getLong("errorCount"), sql); assertFalse(result.next());
        }
    }
    private static List<Integer> ids(Connection connection, String sql) throws Exception {
        List<Integer> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { while (rows.next()) result.add(rows.getInt("id")); }
        return result.stream().sorted().toList();
    }
}
