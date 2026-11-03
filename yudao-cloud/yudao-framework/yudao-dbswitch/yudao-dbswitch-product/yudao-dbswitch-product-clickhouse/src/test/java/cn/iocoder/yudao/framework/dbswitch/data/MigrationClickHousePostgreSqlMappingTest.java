package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyColumnAcceptance;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.CLICKHOUSE;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

/** Real catalog -> registered mapper -> generated DDL -> public FULL/PK_DIFF; no hand-written target type fixture. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationClickHousePostgreSqlMappingTest {
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final String schema = "chpg70_" + token;
    private final String source = "ch70_source_" + token;
    private final String target = "ch70_target_" + token;

    @BeforeAll static void drivers() throws Exception {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        Class.forName("org.postgresql.Driver");
    }
    @BeforeEach void setup() throws Exception { sql(POSTGRESQL, "CREATE SCHEMA " + schema); }
    @AfterEach void cleanup() throws Exception {
        sql(CLICKHOUSE, "DROP TABLE IF EXISTS " + ch(source) + " SYNC", "DROP TABLE IF EXISTS " + ch(target) + " SYNC");
        sql(POSTGRESQL, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test void nativeCatalogKeepsAbsenceDefaultAndGeneratedFactsSeparate() throws Exception {
        assertThrows(SQLException.class, () -> sql(CLICKHOUSE, "CREATE TABLE " + ch(target)
                + " (id Int64 GENERATED ALWAYS AS IDENTITY) ENGINE=MergeTree ORDER BY id"));
        assertThrows(SQLException.class, () -> sql(CLICKHOUSE, "CREATE TABLE " + ch(target)
                + " (id Int64 AUTO_INCREMENT) ENGINE=MergeTree ORDER BY id"));
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source)
                + " (id Int64, amount Nullable(Decimal(38,9)), ordinary Int32 DEFAULT 7, generated Int32 MATERIALIZED id+1)"
                + " ENGINE=MergeTree ORDER BY id");
        try (var connection = open(CLICKHOUSE); var runtime = DbSwitchRuntime.standalone()) {
            var catalog = NativeSchemaReader.read(connection, CLICKHOUSE, "default", source, 10);
            assertTrue(catalog.provenance().nativeProduct(CLICKHOUSE));
            assertTrue(catalog.provenance().serverVersion().startsWith("25.8."));
            var id = catalog.columns().stream().filter(c -> c.getFieldName().equals("id")).findFirst().orElseThrow();
            assertEquals(MetadataValue.State.ABSENT, id.getMetadataEvidence().identity().state());
            assertEquals(TypeFidelity.EXACT, runtime.products().typeMappings().map(POSTGRESQL, id, true).fidelity());
            var amount = catalog.columns().stream().filter(c -> c.getFieldName().equals("amount")).findFirst().orElseThrow();
            assertTrue(amount.isNullable()); assertEquals(38, amount.getMetadataEvidence().numericPrecision().value());
            assertEquals(9, amount.getMetadataEvidence().numericScale().value());
            var ordinary = catalog.columns().stream().filter(c -> c.getFieldName().equals("ordinary")).findFirst().orElseThrow();
            assertEquals("7", ordinary.getMetadataEvidence().defaultExpression().value());
            assertEquals(MetadataValue.State.UNKNOWN, ordinary.getMetadataEvidence().identity().state());
            assertEquals(TypeFidelity.UNSUPPORTED, runtime.products().typeMappings().map(POSTGRESQL, ordinary, false).fidelity());
            var generated = catalog.columns().stream().filter(c -> c.getFieldName().equals("generated")).findFirst().orElseThrow();
            assertEquals(MetadataValue.State.UNSUPPORTED, generated.getMetadataEvidence().generatedExpression().state());
            assertEquals(TypeFidelity.UNSUPPORTED, runtime.products().typeMappings().map(POSTGRESQL, generated, false).fidelity());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void clickHouseToPostgresFullDifferenceReplayPreserveUnsignedDecimalTemporalAndNull(boolean insert) throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source)
                + " (id Int64,u Nullable(UInt64),amount Nullable(Decimal(38,9)),flag Nullable(Bool),day Nullable(Date32),"
                + " moment Nullable(DateTime64(6)),uid Nullable(UUID)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(-9223372036854775808,18446744073709551615,"
                        + "'99999999999999999999999999999.999999999',true,'1900-01-01','1900-01-01 00:00:00.123456'"
                        + ",'550e8400-e29b-41d4-a716-446655440000'),(2,NULL,NULL,NULL,NULL,NULL,NULL)");
        var request = request(CLICKHOUSE, POSTGRESQL, false); request.getTarget().setWriterEngineInsert(insert);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var full = new MigrationService(request, ignored -> { }, null, runtime).run();
            assertTrue(full.getIfAllSuccess().get(), () -> full.getTableResultList().toString());
            assertEquals(TypeFidelity.EXACT, full.getTableResultList().getFirst().getTypeMappingPlan().fidelity());
            assertEquals(List.of("-9223372036854775808|18446744073709551615|99999999999999999999999999999.999999999|true|1900-01-01|1900-01-01 00:00:00.123456|550e8400-e29b-41d4-a716-446655440000",
                    "2|NULL|NULL|NULL|NULL|NULL|NULL"), pgRows());
            // The generated unsigned target domain must reject a negative value as well as carry UInt64.MAX.
            assertThrows(SQLException.class, () -> sql(POSTGRESQL,
                    "INSERT INTO " + pg("copy") + "(id,u) VALUES(9,-1)"));
            sql(CLICKHOUSE, "ALTER TABLE " + ch(source) + " DELETE WHERE id=-9223372036854775808 SETTINGS mutations_sync=2",
                    "ALTER TABLE " + ch(source) + " UPDATE u=18446744073709551615,amount=CAST('-1.000000001' AS Decimal(38,9)),"
                            + "flag=false,day=toDate32('2299-12-31'),moment=toDateTime64('2299-12-31 23:59:59.999999',6)"
                            + ",uid=toUUID('00000000-0000-0000-0000-000000000000') WHERE id=2 SETTINGS mutations_sync=2",
                    "INSERT INTO " + ch(source) + " VALUES(9223372036854775807,0,'0.000000000',true,'1970-01-01',"
                            + "'1970-01-01 00:00:00.000000','00000000-0000-0000-0000-000000000000')");
            request.getTarget().setChangeDataSync(true);
            assertSuccess(request, runtime); var changed = pgRows();
            assertEquals(List.of("2|18446744073709551615|-1.000000001|false|2299-12-31|2299-12-31 23:59:59.999999|00000000-0000-0000-0000-000000000000",
                    "9223372036854775807|0|0.000000000|true|1970-01-01|1970-01-01 00:00:00|00000000-0000-0000-0000-000000000000"), changed);
            assertSuccess(request, runtime); assertEquals(changed, pgRows());
        }
    }

    @Test void postgresToClickHouseRequiresExactPreviewAcknowledgementsThenRunsFullDifferenceReplay() throws Exception {
        sql(POSTGRESQL, "CREATE TABLE " + pg("source")
                + " (id bigint PRIMARY KEY,amount numeric(38,9),flag boolean,uid uuid)",
                "INSERT INTO " + pg("source") + " VALUES(-1,99999999999999999999999999999.999999999,true,"
                        + "'550e8400-e29b-41d4-a716-446655440000'),(2,NULL,NULL,NULL)");
        var request = request(POSTGRESQL, CLICKHOUSE, false);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var metadata = runtime.metadata(dataSource(POSTGRESQL), POSTGRESQL);
            var columns = metadata.queryTableColumnMetaWithNativeEvidence(schema, "source");
            var preview = runtime.products().typeMappings().plan(CLICKHOUSE, columns, List.of("id"), LossyColumnAcceptance.rejectAll());
            assertEquals(TypeFidelity.LOSSY, preview.fidelity());
            assertTrue(preview.column("id").lossCodes().contains("KEY_UNIQUENESS_NOT_ENFORCED"));
            assertTrue(preview.column("amount").lossCodes().contains("NAN_NOT_REPRESENTABLE"));
            assertFalse(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
            assertFalse(chExists(target), "unaccepted mapping must not create the target");
            request.setTypeMappingAcknowledgements(preview.columns().stream().filter(c -> c.fidelity() == TypeFidelity.LOSSY)
                    .map(c -> LossyMappingAcknowledgement.forColumn("default", target, c)).toList());
            var options = new cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions(
                    runtime.products().typeMappings(), LossyMappingAcknowledgement.acceptanceFor(
                    request.getTypeMappingAcknowledgements(), CLICKHOUSE, "default", target));
            var generated = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getDDLCreateTableSQL(
                    runtime.products().metadata(CLICKHOUSE), columns, List.of("id"), "default", target, false, options);
            assertTrue(generated.contains("`amount` Nullable(Decimal(38, 9))"), generated);
            assertTrue(generated.contains("ENGINE=MergeTree"), generated);
            assertThrows(UnsupportedOperationException.class,
                    () -> cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(CLICKHOUSE, generated));
            assertSuccess(request, runtime);
            assertEquals(List.of("-1|99999999999999999999999999999.999999999|true|550e8400-e29b-41d4-a716-446655440000",
                    "2|NULL|NULL|NULL"), chRows());
            sql(POSTGRESQL, "DELETE FROM " + pg("source") + " WHERE id=-1",
                    "UPDATE " + pg("source") + " SET amount=-1.000000001,flag=false WHERE id=2",
                    "INSERT INTO " + pg("source") + " VALUES(3,0,true,'00000000-0000-0000-0000-000000000000')");
            request.getTarget().setChangeDataSync(true); assertSuccess(request, runtime);
            var changed = chRows();
            assertEquals(List.of("2|-1.000000001|false|NULL", "3|0.000000000|true|00000000-0000-0000-0000-000000000000"), changed);
            assertSuccess(request, runtime); assertEquals(changed, chRows());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unsupportedClickHouseStringFailsBeforeExistingPostgresTargetCanChange(boolean difference) throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,note String) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(1,'unsupported')");
        sql(POSTGRESQL, "CREATE TABLE " + pg("copy") + " (id bigint PRIMARY KEY,note text)",
                "INSERT INTO " + pg("copy") + " VALUES(7,'keep')");
        var request = request(CLICKHOUSE, POSTGRESQL, difference);
        if (!difference) { request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true); }
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            assertFalse(result.getIfAllSuccess().get());
            assertEquals(TypeFidelity.UNSUPPORTED, result.getTableResultList().getFirst().getTypeMappingPlan().column("note").fidelity());
        }
        try (var connection = open(POSTGRESQL); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,note FROM " + pg("copy"))) {
            assertTrue(rows.next()); assertEquals(7, rows.getLong(1)); assertEquals("keep", rows.getString(2)); assertFalse(rows.next());
        }
    }

    @Test void duplicateClickHouseSortingKeyDifferenceCannotMutatePostgresTarget() throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,amount Nullable(Decimal(38,9))) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(1,1),(1,2)");
        sql(POSTGRESQL, "CREATE TABLE " + pg("copy") + " (id bigint PRIMARY KEY,amount numeric(38,9))",
                "INSERT INTO " + pg("copy") + " VALUES(7,77)");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request(CLICKHOUSE, POSTGRESQL, true), ignored -> { }, null, runtime).run();
            assertFalse(result.getIfAllSuccess().get());
            assertEquals(TypeFidelity.EXACT, result.getTableResultList().getFirst().getTypeMappingPlan().fidelity());
        }
        try (var connection = open(POSTGRESQL); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,amount FROM " + pg("copy"))) {
            assertTrue(rows.next()); assertEquals(7, rows.getLong(1)); assertEquals("77.000000000", rows.getBigDecimal(2).toPlainString()); assertFalse(rows.next());
        }
    }

    @Test void staleNativePreviewDecisionCannotAuthorizeExistingClickHouseTargetReplacement() throws Exception {
        sql(POSTGRESQL, "CREATE TABLE " + pg("source") + " (id bigint PRIMARY KEY,amount numeric(38,9))",
                "INSERT INTO " + pg("source") + " VALUES(1,1)");
        sql(CLICKHOUSE, "CREATE TABLE " + ch(target) + " (id Int64,amount Nullable(Decimal(38,9))) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(target) + " VALUES(7,77)");
        var request = request(POSTGRESQL, CLICKHOUSE, false); request.getTarget().setTargetDrop(true);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var columns = runtime.metadata(dataSource(POSTGRESQL), POSTGRESQL).queryTableColumnMetaWithNativeEvidence(schema, "source");
            var oldPreview = runtime.products().typeMappings().plan(CLICKHOUSE, columns, List.of("id"), LossyColumnAcceptance.rejectAll());
            request.setTypeMappingAcknowledgements(oldPreview.columns().stream().filter(c -> c.fidelity() == TypeFidelity.LOSSY)
                    .map(c -> LossyMappingAcknowledgement.forColumn("default", target, c)).toList());
            sql(POSTGRESQL, "ALTER TABLE " + pg("source") + " ALTER COLUMN amount TYPE numeric(20,4)");
            assertFalse(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        }
        assertEquals(List.of("7|77.000000000"), rows(CLICKHOUSE, "SELECT id,amount FROM " + ch(target)));
    }

    @Test void sameClickHouseNativeAutoCreateFullDifferenceAndReplayKeepExactNullableTextAndUnsignedValues() throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source)
                + " (id Int64,u Nullable(UInt64),amount Nullable(Decimal(38,9)),note Nullable(String),uid Nullable(UUID)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(-1,18446744073709551615,'99999999999999999999999999999.999999999',"
                        + "unhex('E4B8ADE69687000A5C27'),'550e8400-e29b-41d4-a716-446655440000'),(2,NULL,NULL,NULL,NULL)");
        var request = request(CLICKHOUSE, CLICKHOUSE, false);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var columns = runtime.metadata(dataSource(CLICKHOUSE), CLICKHOUSE).queryTableColumnMetaWithNativeEvidence("default", source);
            var ddl = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getDDLCreateTableSQL(
                    runtime.products().metadata(CLICKHOUSE), columns, List.of("id"), "default", target, false);
            assertTrue(ddl.contains("`u` Nullable(UInt64)"), ddl); assertTrue(ddl.contains("`note` Nullable(String)"), ddl);
            assertTrue(ddl.contains("ENGINE=MergeTree"), ddl);
            assertSuccess(request, runtime);
            var exactQuery = "SELECT id,toString(u),toString(amount),hex(note),toString(uid) FROM ";
            assertEquals(rows(CLICKHOUSE, exactQuery + ch(source) + " ORDER BY id"), rows(CLICKHOUSE, exactQuery + ch(target) + " ORDER BY id"));
            assertEquals(List.of("-1|18446744073709551615|99999999999999999999999999999.999999999|E4B8ADE69687000A5C27|550e8400-e29b-41d4-a716-446655440000",
                    "2|NULL|NULL|NULL|NULL"), rows(CLICKHOUSE, exactQuery + ch(target) + " ORDER BY id"));
            sql(CLICKHOUSE, "ALTER TABLE " + ch(source) + " DELETE WHERE id=-1 SETTINGS mutations_sync=2",
                    "ALTER TABLE " + ch(source) + " UPDATE u=18446744073709551615,amount=CAST('-1.000000001' AS Decimal(38,9)),note='更新' WHERE id=2 SETTINGS mutations_sync=2",
                    "INSERT INTO " + ch(source) + " VALUES(3,0,'0.000000000','',NULL)");
            request.getTarget().setChangeDataSync(true); assertSuccess(request, runtime);
            var changed = rows(CLICKHOUSE, exactQuery + ch(source) + " ORDER BY id");
            assertEquals(changed, rows(CLICKHOUSE, exactQuery + ch(target) + " ORDER BY id"));
            assertSuccess(request, runtime); assertEquals(changed, rows(CLICKHOUSE, exactQuery + ch(target) + " ORDER BY id"));
        }
    }

    @Test void malformedSameClickHouseStringIsRejectedBeforeExistingTargetDropOrTruncate() throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,note String) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(1,unhex('FF'))",
                "CREATE TABLE " + ch(target) + " (id Int64,note String) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(target) + " VALUES(7,'keep')");
        var request = request(CLICKHOUSE, CLICKHOUSE, false); request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertFalse(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        }
        assertEquals(List.of("7|keep"), rows(CLICKHOUSE, "SELECT id,note FROM " + ch(target)));
    }

    @Test void admittedUuidCopyPreservesValuesWhenReplacingAnExistingPostgresTarget() throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,uid Nullable(UUID)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(1,'550e8400-e29b-41d4-a716-446655440000')");
        sql(POSTGRESQL, "CREATE TABLE " + pg("copy") + " (id bigint PRIMARY KEY,uid uuid)",
                "INSERT INTO " + pg("copy") + " VALUES(7,'00000000-0000-0000-0000-000000000000')");
        var request = request(CLICKHOUSE, POSTGRESQL, false); request.getTarget().setWriterEngineInsert(false);
        request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
            assertEquals(TypeFidelity.EXACT, result.getTableResultList().getFirst().getTypeMappingPlan().fidelity());
        }
        assertEquals(List.of("1|550e8400-e29b-41d4-a716-446655440000"), rows(POSTGRESQL, "SELECT id,uid FROM " + pg("copy")));
    }

    @Test void sameClickHouseFiniteFloatAutoCreateFullDifferenceAndReplayPreserveNativeBitsAndNull() throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,f32 Nullable(Float32),f64 Nullable(Float64)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(1,0.5,-0.0),(2,NULL,NULL)");
        var request = request(CLICKHOUSE, CLICKHOUSE, false);
        var bitQuery = "SELECT id,hex(reinterpretAsFixedString(f32)),hex(reinterpretAsFixedString(f64)) FROM ";
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertSuccess(request, runtime);
            assertEquals(rows(CLICKHOUSE, bitQuery + ch(source) + " ORDER BY id"), rows(CLICKHOUSE, bitQuery + ch(target) + " ORDER BY id"));
            sql(CLICKHOUSE, "ALTER TABLE " + ch(source) + " DELETE WHERE id=1 SETTINGS mutations_sync=2",
                    "ALTER TABLE " + ch(source) + " UPDATE f32=toFloat32('3.4028234663852886e38'),f64=toFloat64('1.7976931348623157e308') WHERE id=2 SETTINGS mutations_sync=2",
                    "INSERT INTO " + ch(source) + " VALUES(3,NULL,0.5)");
            request.getTarget().setChangeDataSync(true); assertSuccess(request, runtime);
            var exact = rows(CLICKHOUSE, bitQuery + ch(source) + " ORDER BY id");
            assertEquals(exact, rows(CLICKHOUSE, bitQuery + ch(target) + " ORDER BY id"));
            assertSuccess(request, runtime); assertEquals(exact, rows(CLICKHOUSE, bitQuery + ch(target) + " ORDER BY id"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"nan", "inf", "-inf"})
    void nonfiniteClickHouseFloatIsRejectedBeforeExistingTargetDropOrTruncate(String special) throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,value Nullable(Float64)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + " VALUES(1,toFloat64('" + special + "'))",
                "CREATE TABLE " + ch(target) + " (id Int64,value Nullable(Float64)) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(target) + " VALUES(7,77)");
        var request = request(CLICKHOUSE, CLICKHOUSE, false); request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertFalse(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        }
        assertEquals(List.of("7|77.0"), rows(CLICKHOUSE, "SELECT id,value FROM " + ch(target)));
    }

    @ParameterizedTest @ValueSource(strings = {"DEFAULT 7", "MATERIALIZED id+1", "ALIAS id+1"})
    void nativeDefaultOrGeneratedColumnCannotAuthorizeExistingClickHouseTargetReplacement(String generation) throws Exception {
        sql(CLICKHOUSE, "CREATE TABLE " + ch(source) + " (id Int64,value Int64 " + generation + ") ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(source) + "(id) VALUES(1)",
                "CREATE TABLE " + ch(target) + " (id Int64,value Int64) ENGINE=MergeTree ORDER BY id",
                "INSERT INTO " + ch(target) + " VALUES(7,77)");
        // Observe the actual generation spelling independently of SELECT *, which excludes ALIAS columns.
        try (var connection = open(CLICKHOUSE); var statement = connection.prepareStatement(
                "SELECT default_kind,default_expression FROM system.columns WHERE database='default' AND table=? AND name='value'")) {
            statement.setString(1, source);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals(generation.split(" ")[0], rows.getString(1));
                assertFalse(rows.getString(2).isBlank()); assertFalse(rows.next());
            }
        }
        var request = request(CLICKHOUSE, CLICKHOUSE, false); request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertFalse(new MigrationService(request, ignored -> { }, null, runtime).run().getIfAllSuccess().get());
        }
        assertEquals(List.of("7|77"), rows(CLICKHOUSE, "SELECT id,value FROM " + ch(target)));
    }

    private MigrationRequest request(ProductTypeEnum from, ProductTypeEnum to, boolean difference) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(from); input.setUrl(url(from)); input.setUsername(user(from)); input.setPassword("");
        input.setDriverClassName(from.getDriveClassName()); input.setSourceSchema(from == CLICKHOUSE ? "default" : schema);
        var inputTable = from == CLICKHOUSE ? source : "source"; var outputTable = to == CLICKHOUSE ? target : "copy";
        input.setSourceIncludes(inputTable); input.setPoolSize(2); input.setFetchSize(2);
        input.setRegexTableMapper(List.of(new PatternMapper("^" + inputTable + "$", outputTable))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(to); output.setUrl(url(to)); output.setUsername(user(to)); output.setPassword("");
        output.setDriverClassName(to.getDriveClassName()); output.setTargetSchema(to == CLICKHOUSE ? "default" : schema);
        output.setTargetTable(outputTable); output.setPoolSize(2); output.setWriterEngineInsert(true); output.setChangeDataSync(difference);
        return request;
    }
    private static void assertSuccess(MigrationRequest request, DbSwitchRuntime runtime) throws Exception {
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
    }
    private List<String> pgRows() throws Exception { return rows(POSTGRESQL, "SELECT id,u,amount,flag,day,moment,uid FROM " + pg("copy") + " ORDER BY id"); }
    private List<String> chRows() throws Exception { return rows(CLICKHOUSE, "SELECT id,amount,flag,uid FROM " + ch(target) + " ORDER BY id"); }
    private static List<String> rows(ProductTypeEnum product, String sql) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = open(product); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                var cells = new ArrayList<String>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    var value = rows.getObject(i);
                    cells.add(value == null ? "NULL" : value instanceof java.math.BigDecimal number ? number.toPlainString()
                            : value instanceof Boolean bool ? bool.toString() : rows.getString(i));
                }
                result.add(String.join("|", cells));
            }
        }
        return result;
    }
    private static boolean chExists(String table) throws Exception {
        try (var connection = open(CLICKHOUSE); var statement = connection.prepareStatement("SELECT count() FROM system.tables WHERE database='default' AND name=?")) {
            statement.setString(1, table); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1) != 0; }
        }
    }
    private String pg(String table) { return '"' + schema + "\".\"" + table + '"'; }
    private static String ch(String table) { return ClickhouseSql.table("default", table); }
    private static String url(ProductTypeEnum product) { return System.getProperty(product == CLICKHOUSE ? "datahub.test.clickhouse.url" : "datahub.test.postgres.url"); }
    private static String user(ProductTypeEnum product) { return product == CLICKHOUSE ? "default" : System.getProperty("datahub.test.postgres.user", "postgres"); }
    private static Connection open(ProductTypeEnum product) throws SQLException { return DriverManager.getConnection(url(product), user(product), ""); }
    private static DataSource dataSource(ProductTypeEnum product) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return open(product); }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
    private static void sql(ProductTypeEnum product, String... sql) throws SQLException {
        try (var connection = open(product); var statement = connection.createStatement()) {
            statement.setQueryTimeout(30); for (var command : sql) statement.execute(command);
        }
    }
}
