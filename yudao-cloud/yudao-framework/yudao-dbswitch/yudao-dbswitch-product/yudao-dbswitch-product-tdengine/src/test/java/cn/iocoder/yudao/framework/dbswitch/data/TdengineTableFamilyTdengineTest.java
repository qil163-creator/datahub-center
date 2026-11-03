package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.TdengineDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.tdengine.ws.url", matches="jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfEnvironmentVariable(named="DBSWITCH_TEST_TDENGINE_PASSWORD", matches=".+")
class TdengineTableFamilyTdengineTest {
    private static final List<String> FIELDS = List.of("ts", "amount", "u", "note");
    private static final Map<String, String> MAPPING = Map.of("ts", "ts", "amount", "amount", "u", "u", "note", "note");
    private static final String TAG = "中文'\\\n😀";
    private String sourceSchema, targetSchema;
    @BeforeEach void setup() throws Exception {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        sourceSchema = "td_family_" + UUID.randomUUID().toString().replace("-", ""); targetSchema = sourceSchema + "_t";
        sql("CREATE DATABASE " + sourceSchema + " VGROUPS 1 BUFFER 8 PRECISION 'ns'");
        sql("CREATE DATABASE " + targetSchema + " VGROUPS 1 BUFFER 8 PRECISION 'ns'");
        createStable(sourceSchema, "metrics"); createChild(sourceSchema, "child_a", TAG); createChild(sourceSchema, "child_b", "second");
    }
    @AfterEach void cleanup() throws Exception {
        if (sourceSchema != null) sql("DROP DATABASE IF EXISTS " + sourceSchema);
        if (targetSchema != null) sql("DROP DATABASE IF EXISTS " + targetSchema);
    }
    @Test void publicFamilyFullAndDiffPreserveLeafIdentityTagsUnsignedAndNativeStructure() throws Exception {
        write(sourceSchema, "child_a", row(1, "first")); write(sourceSchema, "child_b", row(1, "same timestamp different child"));
        try (var runtime = DbSwitchRuntime.standalone()) {
            var full = new MigrationService(request(false, "metrics,child_a"), ignored -> {}, null, runtime).run();
            assertTrue(full.getIfAllSuccess().get(), () -> full.getTableResultList().toString());
            assertEquals(2, full.getTableResultList().size(), "A selected supertable and its selected child must not duplicate a task");
            assertRow(targetSchema, "child_a_copy", "first"); assertRow(targetSchema, "child_b_copy", "same timestamp different child");
            assertNativeEqual("child_a", "child_a_copy"); assertNativeEqual("child_b", "child_b_copy");
            write(sourceSchema, "child_a", row(1, "changed"));
            var diff = new MigrationService(request(true, "metrics"), ignored -> {}, null, runtime).run();
            assertTrue(diff.getIfAllSuccess().get(), () -> diff.getTableResultList().toString());
            assertRow(targetSchema, "child_a_copy", "changed"); assertRow(targetSchema, "child_b_copy", "same timestamp different child");
            assertTrue(new MigrationService(request(true, "metrics"), ignored -> {}, null, runtime).run().getIfAllSuccess().get());
        }
    }
    @Test void emptyChildRetainsTagsAndEmptyStableStillCreatesAConcreteStructureUnit() throws Exception {
        createStable(sourceSchema, "empty_family");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var full = new MigrationService(request(false, "metrics,empty_family"), ignored -> {}, null, runtime).run();
            assertTrue(full.getIfAllSuccess().get(), () -> full.getTableResultList().toString());
            assertEquals(3, full.getTableResultList().size());
            assertNativeEqual("child_a", "child_a_copy");
            var op = operator(source(null));
            assertEquals(structure(op.describeNativeTable(sourceSchema, "empty_family").document()), structure(op.describeNativeTable(targetSchema, "empty_family_copy").document()));
            assertEquals(0, count(targetSchema, "empty_family_copy"));
        }
    }
    @Test void changedTagFailsPublicDifferenceBeforeAnyMeasurementWrite() throws Exception {
        write(sourceSchema, "child_a", row(1, "before"));
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertTrue(new MigrationService(request(false, "child_a"), ignored -> {}, null, runtime).run().getIfAllSuccess().get());
            sql("ALTER TABLE " + name(sourceSchema, "child_a") + " SET TAG site='changed'");
            write(sourceSchema, "child_a", row(1, "after"));
            var result = new MigrationService(request(true, "child_a"), ignored -> {}, null, runtime).run();
            assertFalse(result.getIfAllSuccess().get()); assertRow(targetSchema, "child_a_copy", "before");
            try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT TAGS site FROM " + name(targetSchema, "child_a_copy"))) {
                assertTrue(r.next()); assertEquals(TAG, r.getString(1));
            }
        }
    }
    @Test void explicitLeafReplacementPreservesUnselectedSiblingAndParent() throws Exception {
        createStable(targetSchema, "metrics"); createChild(targetSchema, "unselected", "keep"); write(targetSchema, "unselected", row(1, "sibling"));
        var op = operator(source(null)); var evidence = op.describeNativeTable(sourceSchema, "child_a");
        op.prepareNativeTarget(evidence, targetSchema, "selected", MAPPING, false).execute();
        write(targetSchema, "selected", row(1, "old"));
        var replacement = op.prepareNativeTarget(evidence, targetSchema, "selected", MAPPING, true);
        assertTrue(replacement.requiresExclusiveDdlOwnership());
        assertEquals(Set.of("selected", "metrics"), replacement.recoveryResources().stream().map(resource -> resource.name()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(replacement.recoveryResources().stream().allMatch(resource -> resource.expectedPhysicalIdentity() != null));
        replacement.execute();
        assertEquals(0, count(targetSchema, "selected")); assertRow(targetSchema, "unselected", "sibling");
        assertNativeEqual("child_a", "selected");
    }
    @Test void incompatibleParentAndSameStructureReplacementAreDetectedBeforeDestructiveDdl() throws Exception {
        sql("CREATE STABLE " + name(targetSchema, "metrics") + "(ts TIMESTAMP,n BIGINT) TAGS(site NCHAR(64))");
        var op = operator(source(null)); var evidence = op.describeNativeTable(sourceSchema, "child_a");
        assertThrows(IllegalStateException.class, () -> op.prepareNativeTarget(evidence, targetSchema, "selected", MAPPING, true));
        sql("DROP STABLE " + name(targetSchema, "metrics"));
        op.prepareNativeTarget(evidence, targetSchema, "selected", MAPPING, false).execute();
        var plan = op.prepareNativeTarget(evidence, targetSchema, "selected", MAPPING, true);
        sql("DROP TABLE " + name(targetSchema, "selected")); createChild(targetSchema, "selected", TAG); write(targetSchema, "selected", row(1, "new owner"));
        assertThrows(IllegalStateException.class, plan::execute);
        assertRow(targetSchema, "selected", "new owner");
    }
    @Test void nativeDdlLostAcknowledgementIsUnknownAndPlanIsSingleUse() throws Exception {
        var fault = new AtomicBoolean(); var op = operator(source(fault));
        var plan = op.prepareNativeTarget(op.describeNativeTable(sourceSchema, "child_a"), targetSchema, "selected", MAPPING, false);
        assertFalse(plan.requiresExclusiveDdlOwnership());
        var before = plan.recoveryResources(); assertEquals(2, before.size());
        assertTrue(before.stream().allMatch(resource -> resource.expectedPhysicalIdentity() == null));
        fault.set(true); assertThrows(UnconfirmedWriteException.class, plan::execute); assertFalse(fault.get());
        assertEquals(before, plan.recoveryResources(), "UNKNOWN must retain the pre-plan generation evidence; null is not proof of absence");
        assertThrows(IllegalStateException.class, plan::execute);
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SHOW " + targetSchema + ".STABLES")) { assertTrue(r.next()); }
    }
    @Test void supertableReplacementNeverCascadesIntoUnselectedChildren() throws Exception {
        createStable(targetSchema, "metrics"); createChild(targetSchema, "unselected", "keep"); write(targetSchema, "unselected", row(1, "sibling"));
        var op = operator(source(null)); var evidence = op.describeNativeTable(sourceSchema, "metrics");
        assertThrows(IllegalStateException.class, () -> op.prepareNativeTarget(evidence, targetSchema, "metrics", MAPPING, true));
        assertRow(targetSchema, "unselected", "sibling");
    }
    @Test void selectingStableAndChildIsDeduplicatedWithoutFlatteningTagsIntoMeasurementFields() {
        var op = operator(source(null));
        var selected = op.planMigrationSources(List.of(table("metrics"), table("child_a")));
        assertEquals(List.of("child_a", "child_b"), selected.stream().map(TableDescription::getTableName).toList());
        assertEquals(FIELDS, op.nativeMigrationColumns(sourceSchema, "child_a").stream().map(column -> column.getFieldName()).toList());
    }
    @Test void publicReplacementRequiresExplicitDdlOwnershipBeforeDroppingExistingLeaf() throws Exception {
        write(sourceSchema, "child_a", row(1, "source"));
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertTrue(new MigrationService(request(false, "child_a"), ignored -> {}, null, runtime).run().getIfAllSuccess().get());
            write(targetSchema, "child_a_copy", row(1, "existing target"));
            String before = uid(targetSchema, "child_a_copy");
            var rejected = request(false, "child_a"); rejected.getTarget().setTargetDrop(true);
            var failure = new MigrationService(rejected, ignored -> {}, null, runtime).run();
            assertFalse(failure.getIfAllSuccess().get()); assertEquals(before, uid(targetSchema, "child_a_copy"));
            assertRow(targetSchema, "child_a_copy", "existing target");
            assertFalse(failure.getTableResultList().getFirst().getNativeRecoveryResources().isEmpty());
            var permitted = request(false, "child_a"); permitted.getTarget().setTargetDrop(true);
            permitted.getTarget().setExclusiveTargetDdlOwnership(true);
            var completed = new MigrationService(permitted, ignored -> {}, null, runtime).run();
            assertTrue(completed.getIfAllSuccess().get(), () -> completed.getTableResultList().toString());
            assertNotEquals(before, uid(targetSchema, "child_a_copy")); assertRow(targetSchema, "child_a_copy", "source");
        }
    }
    @Test void samePhysicalSourceAndTargetReplacementFailsEvenWithDeclaredDdlOwnership() throws Exception {
        write(sourceSchema, "child_a", row(1, "source survives")); String before = uid(sourceSchema, "child_a");
        var request = request(false, "child_a"); request.getSource().getFirst().setRegexTableMapper(List.of());
        request.getTarget().setTargetSchema(sourceSchema); request.getTarget().setTargetDrop(true);
        request.getTarget().setExclusiveTargetDdlOwnership(true);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> {}, null, runtime).run();
            assertFalse(result.getIfAllSuccess().get()); assertEquals(before, uid(sourceSchema, "child_a"));
            assertRow(sourceSchema, "child_a", "source survives");
        }
    }
    private TableDescription table(String name) { var table = new TableDescription(); table.setSchemaName(sourceSchema); table.setTableName(name); table.setTableType("TABLE"); return table; }
    private static void createStable(String schema, String table) throws Exception {
        sql("CREATE STABLE " + name(schema, table) + "(ts TIMESTAMP,amount DECIMAL(38,9),u BIGINT UNSIGNED,note NCHAR(64))"
                + " TAGS(site NCHAR(64),group_id INT,binary_tag VARBINARY(8)) COMMENT 'parent comment'");
    }
    private static void createChild(String schema, String table, String tag) throws Exception {
        sql("CREATE TABLE " + name(schema, table) + " USING " + name(schema, "metrics") + " TAGS(" + literal(tag) + ",NULL,'\\x0001ff') COMMENT 'child comment' TTL 5");
    }
    private static Object[] row(int second, String note) { return new Object[]{Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456789Z").plusSeconds(second)),
            new BigDecimal("12345678901234567890123456789.123456789"), new java.math.BigInteger("18446744073709551615"), note}; }
    private static void write(String schema, String table, Object[] row) {
        var writer = new TdengineWriterImpl(source(null)); writer.prepareWrite(schema, table, FIELDS); assertEquals(1, writer.write(FIELDS, Collections.singletonList(row)));
    }
    private static void assertRow(String schema, String table, String note) throws Exception {
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + name(schema, table))) {
            assertTrue(r.next()); assertEquals(row(1, note)[0], r.getTimestamp(1)); assertEquals(row(1, note)[1], r.getBigDecimal(2));
            assertEquals(row(1, note)[2], r.getObject(3)); assertEquals(note, r.getString(4)); assertFalse(r.next());
        }
    }
    private void assertNativeEqual(String sourceTable, String targetTable) {
        var op = operator(source(null)); assertEquals(structure(op.describeNativeTable(sourceSchema, sourceTable).document()), structure(op.describeNativeTable(targetSchema, targetTable).document()));
    }
    private static Object structure(String document) {
        var json = cn.hutool.json.JSONUtil.parseObj(document);
        json.remove("sourceCluster"); json.remove("sourceUid"); json.remove("sourceKind"); return json;
    }
    private static long count(String schema, String table) throws Exception {
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + name(schema, table))) { assertTrue(r.next()); return r.getLong(1); }
    }
    private static String uid(String schema, String table) throws Exception {
        try (var c = open(); var s = c.createStatement(); var r = s.executeQuery("SELECT uid FROM information_schema.ins_tables WHERE db_name="
                + literal(schema) + " AND table_name=" + literal(table))) { assertTrue(r.next()); return r.getString(1); }
    }
    private MigrationRequest request(boolean difference, String tables) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.TDENGINE); input.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        input.setUrl(url()); input.setUsername("root"); input.setPassword(password()); input.setSourceSchema(sourceSchema); input.setSourceIncludes(tables);
        input.setPoolSize(3); input.setFetchSize(2); input.setRegexTableMapper(List.of(new PatternMapper("^(.*)$", "$1_copy"))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.TDENGINE); output.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        output.setUrl(url()); output.setUsername("root"); output.setPassword(password()); output.setTargetSchema(targetSchema);
        output.setPoolSize(4); output.setWriterEngineInsert(true); output.setChangeDataSync(difference); output.setBatchLimits(new BatchLimits(20, 16384, 2048, 100, 1000));
        return request;
    }
    private static TdengineDatabaseOperator operator(DataSource source) { return new TdengineDatabaseOperator(source); }
    private static String url() { return System.getProperty("datahub.test.tdengine.ws.url"); }
    private static String password() { return System.getenv("DBSWITCH_TEST_TDENGINE_PASSWORD"); }
    private static Connection open() throws SQLException { return DriverManager.getConnection(url(), "root", password()); }
    private static String name(String schema, String table) { return TdengineJdbcSupport.table(schema, table); }
    private static String literal(String text) { return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"; }
    private static void sql(String sql) throws Exception { try (var c = open(); var s = c.createStatement()) { s.setQueryTimeout(20); s.execute(sql); } }
    private static DataSource source(AtomicBoolean fault) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = open();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (Set.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Native TDengine DDL cannot use JDBC transactions");
                    try {
                        Object value = method.invoke(real, args);
                        if (fault != null && method.getName().equals("createStatement")) {
                            var statement = (Statement) value;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (p, m, a) -> {
                                try { Object result = m.invoke(statement, a);
                                    if (m.getName().equals("execute") && ((String)a[0]).startsWith("CREATE STABLE") && fault.compareAndSet(true, false))
                                        throw new SQLException("injected native DDL response loss");
                                    return result;
                                } catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return value;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
}
