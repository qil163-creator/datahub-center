package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.governance.service.master.MasterModelSchemaPublisher.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实组件与私有本机 PostgreSQL；所有表、数据和故障注入均位于本次随机 schema。 */
@EnabledIfSystemProperty(named = "datahub.test.master.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class MasterModelSchemaPublisherPostgreSqlTest {
    private Connection control;
    private String schema;
    private long originalOid;
    private DbSwitchRuntime realRuntime;
    private DbSwitchRuntime runtime;
    private MasterDatabaseResolver.ConnectionInfo source;
    private MasterModelSchemaPublisher publisher;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.master.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control = DriverManager.getConnection(url, "codex_test", "");
        schema = "masterschema_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        sql("CREATE TABLE person(code varchar(64) PRIMARY KEY, name varchar(64), score numeric(12,2) DEFAULT 0, created_at timestamp DEFAULT CURRENT_TIMESTAMP)");
        sql("INSERT INTO person(code,name) VALUES('P1','physical-data-kept')");
        originalOid = number("SELECT '" + schema + ".person'::regclass::oid");
        realRuntime = DbSwitchRuntime.standalone();
        runtime = spy(realRuntime);
        source = new MasterDatabaseResolver.ConnectionInfo();
        source.setDatabaseId(11L);
        source.setProductType(ProductTypeEnum.POSTGRESQL);
        source.setJdbcUrl(url);
        source.setUsername("codex_test");
        source.setPassword("");
        source.setSchema(schema);
        publisher = new MasterModelSchemaPublisher();
        ReflectionTestUtils.setField(publisher, "dbSwitchRuntime", runtime);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (control != null) {
            try {
                if (schema != null && schema.matches("masterschema_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE");
            } finally { control.close(); }
        }
        if (realRuntime != null) realRuntime.close();
    }

    @Test
    void addColumnKeepsRowsPrimaryKeyTableIdentityAndOrdinaryDefaults() throws Exception {
        Map<String, ColumnDescription> before = observed();
        var result = publisher.synchronize(source, "person", "person", withEmail());
        assertEquals(APPLIED, result.status(), result.diagnostics().toString());
        Map<String, ColumnDescription> after = observed();
        assertTrue(after.containsKey("email"));
        assertEquals(before.get("score").getMetadataEvidence().defaultExpression(), after.get("score").getMetadataEvidence().defaultExpression());
        assertEquals(before.get("created_at").getMetadataEvidence().defaultExpression(), after.get("created_at").getMetadataEvidence().defaultExpression());
        assertEquals(List.of("code"), NativeSchemaReader.read(control, ProductTypeEnum.POSTGRESQL, schema, "person", 10).primaryKeys());
        assertProtectedRows();
        sql("INSERT INTO person(code,name) VALUES('P2','defaults-kept')");
        assertEquals(1, number("SELECT count(*) FROM person WHERE code='P2' AND score=0 AND created_at IS NOT NULL"));
    }

    @Test
    void laterDdlFailureRollsBackEarlierAddAndKeepsData() throws Exception {
        List<MasterColumnDO> desired = new ArrayList<>(ordinary());
        desired.add(1, column("email", ColumnMetaData.TYPE_STRING, 128, null, false));
        desired.stream().filter(column -> column.getName().equals("name")).findFirst().orElseThrow().setFieldLength(3);
        var result = publisher.synchronize(source, "person", "person", desired);
        assertEquals(NOT_APPLIED, result.status(), result.diagnostics().toString());
        assertFalse(observed().containsKey("email"));
        assertEquals(64L, observed().get("name").getMetadataEvidence().characterLength().value());
        assertProtectedRows();
    }

    @Test
    void verifyOnlyDoesNotApplyMismatchAndAcceptsMatchingLockedTarget() throws Exception {
        var mismatch = publisher.verify(source, "person", "person", withEmail());
        assertEquals(NOT_APPLIED, mismatch.status());
        assertEquals(List.of("SCHEMA_TARGET_NOT_MATCHED"), mismatch.diagnostics());
        assertFalse(observed().containsKey("email"));
        assertEquals(APPLIED, publisher.synchronize(source, "person", "person", withEmail()).status());
        assertEquals(APPLIED, publisher.verify(source, "person", "person", withEmail()).status());
        assertProtectedRows();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cast-default", "function-default", "identity", "generated", "dependency"})
    void unsupportedNativeSemanticsAreRejectedWithoutErasingThem(String feature) throws Exception {
        switch (feature) {
            case "cast-default" -> sql("ALTER TABLE person ALTER COLUMN name SET DEFAULT 'unchanged'");
            case "function-default" -> sql("ALTER TABLE person ALTER COLUMN score SET DEFAULT random()");
            case "identity" -> sql("ALTER TABLE person ADD COLUMN native_identity bigint GENERATED ALWAYS AS IDENTITY");
            case "generated" -> sql("ALTER TABLE person ADD COLUMN native_generated integer GENERATED ALWAYS AS (length(name)) STORED");
            case "dependency" -> sql("ALTER TABLE person ADD CONSTRAINT score_nonnegative CHECK(score>=0)");
            default -> fail("Unexpected fixture");
        }
        Map<String, ColumnDescription> before = observed();
        var result = publisher.synchronize(source, "person", "person", withEmail());
        assertEquals(NOT_APPLIED, result.status(), result.diagnostics().toString());
        assertFalse(observed().containsKey("email"));
        assertEquals(before.keySet(), observed().keySet());
        assertEquals(before.get("name").getMetadataEvidence().defaultExpression(), observed().get("name").getMetadataEvidence().defaultExpression());
        assertEquals(before.get("score").getMetadataEvidence().defaultExpression(), observed().get("score").getMetadataEvidence().defaultExpression());
        assertProtectedRows();
    }

    @Test
    void lostCommitAcknowledgementIsUnknownEvenAfterConfirmedRollback() throws Exception {
        doAnswer(call -> {
            Connection owned = realRuntime.openConnection(ProductTypeEnum.POSTGRESQL, null, source.getJdbcUrl(), "codex_test", "");
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(owned, args);
                    if (method.getName().equals("commit")) throw new SQLException("fixture committed, acknowledgement lost");
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }).when(runtime).openConnection(ProductTypeEnum.POSTGRESQL, null, source.getJdbcUrl(), "codex_test", "");
        var result = publisher.synchronize(source, "person", "person", withEmail());
        assertEquals(UNKNOWN, result.status());
        assertEquals(List.of("SCHEMA_COMMIT_UNCONFIRMED"), result.diagnostics());
        assertTrue(observed().containsKey("email"));
        assertProtectedRows();
    }

    private static List<MasterColumnDO> ordinary() {
        return List.of(column("code", ColumnMetaData.TYPE_STRING, 64, null, true),
                column("name", ColumnMetaData.TYPE_STRING, 64, null, false),
                column("score", ColumnMetaData.TYPE_BIGNUMBER, 12, 2, false),
                column("created_at", ColumnMetaData.TYPE_TIMESTAMP, null, null, false));
    }

    private static List<MasterColumnDO> withEmail() {
        List<MasterColumnDO> result = new ArrayList<>(ordinary());
        result.add(column("email", ColumnMetaData.TYPE_STRING, 128, null, false));
        return result;
    }

    private static MasterColumnDO column(String name, int type, Integer length, Integer scale, boolean primary) {
        return MasterColumnDO.builder().name(name).fieldType(type).fieldLength(length).fieldScale(scale).nullable(primary ? 0 : 1)
                .pk(primary ? 1 : 0).build();
    }

    private Map<String, ColumnDescription> observed() throws Exception {
        return NativeSchemaReader.read(control, ProductTypeEnum.POSTGRESQL, schema, "person", 10).columns().stream()
                .collect(Collectors.toMap(ColumnDescription::getFieldName, Function.identity()));
    }

    private void assertProtectedRows() throws Exception {
        assertEquals(originalOid, number("SELECT '" + schema + ".person'::regclass::oid"));
        assertEquals(1, number("SELECT count(*) FROM person WHERE code='P1' AND name='physical-data-kept'"));
    }

    private void sql(String query) throws Exception { try (var statement = control.createStatement()) { statement.execute(query); } }
    private long number(String query) throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery(query)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }
}
