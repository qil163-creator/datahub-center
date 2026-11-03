package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Actual basic PostgreSQL reads on the supplied fixture; no native-DDL/version certification. */
@Timeout(20)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class BasicTableMetadataPostgreSqlTest {
    private Connection control;
    private String schema;
    private final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), networkCalls = new AtomicInteger(), indexCalls = new AtomicInteger();
    private final AtomicReference<Connection> owned = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        control = open(); schema = "basic68pg_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(a integer,z integer,note text DEFAULT 'default fixture',PRIMARY KEY(z,a))");
        sql("CREATE INDEX basic68_note ON " + schema + ".rows(note)");
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) try (var observer = control) {
            assertTrue(schema.matches("basic68pg_[a-f0-9]{32}")); sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void realColumnsAndOrderedKeysDoNotAddNetworkDeadlineOrDefaultsAndIndexes() throws Exception {
        var result = service().queryTableColumnsAndKeys(System.getProperty("datahub.test.postgres.url"), null, null, schema, "rows");
        assertEquals(List.of("a", "z", "note"), result.columns().stream().map(column -> column.getFieldName()).toList());
        assertEquals(List.of("z", "a"), result.primaryKeys());
        assertTrue(result.columns().stream().allMatch(column -> !column.isPk() && column.getDefaultValue() == null && column.getIndexes().isEmpty()));
        assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertTrue(owned.get().isClosed());
        assertEquals(0, networkCalls.get()); assertEquals(0, indexCalls.get());
    }

    @Test void keylessAndQuotedPhysicalNamesRemainBasicReads() throws Exception {
        sql("CREATE TABLE " + schema + ".\"Order;_68\"(\"Select%\" bigint,note text)");
        var result = service().queryTableColumnsAndKeys(System.getProperty("datahub.test.postgres.url"), null, null, schema, "Order;_68");
        assertEquals(List.of("Select%", "note"), result.columns().stream().map(column -> column.getFieldName()).toList());
        assertTrue(result.primaryKeys().isEmpty()); assertEquals(1, opens.get()); assertEquals(1, closes.get());
    }

    @Test void anAbsentRelationCannotReturnAFalseEmptySuccess() throws Exception {
        assertThrows(RuntimeException.class, () -> service().queryTableColumnsAndKeys(
                System.getProperty("datahub.test.postgres.url"), null, null, schema, "absent"));
        assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertTrue(owned.get().isClosed());
    }

    private MetaDataByJdbcServiceImpl service() {
        return new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, DbSwitchProductRegistry.discover(), (url, user, password) -> {
            opens.incrementAndGet(); Connection physical = open(); owned.set(physical);
            var actualMetadata = physical.getMetaData();
            var metadata = (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (p,m,a) -> {
                if (m.getName().equals("getIndexInfo")) { indexCalls.incrementAndGet(); throw new AssertionError("full index enrichment is forbidden"); }
                return invoke(actualMetadata, m, a);
            });
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
                if (m.getName().equals("getMetaData")) return metadata;
                if (m.getName().equals("getNetworkTimeout") || m.getName().equals("setNetworkTimeout")) {
                    networkCalls.incrementAndGet(); throw new SQLFeatureNotSupportedException("network deadline unsupported");
                }
                if (m.getName().equals("close")) closes.incrementAndGet();
                return invoke(physical, m, a);
            });
        });
    }
    private Connection open() throws java.sql.SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
    }
    private void sql(String statement) throws Exception { try (var query = control.createStatement()) { query.execute(statement); } }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
