package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Installed product contract only; real server evidence is in ConnectionProbeIntegrationTest. */
class ConnectionProbeTest {
    static Stream<ProductTypeEnum> installedProducts() {
        var registry = DbSwitchProductRegistry.discover();
        assertEquals(36, registry.products().size());
        return registry.products().stream().map(product -> product.product()).filter(product -> product.getIndex() <= 27);
    }

    @ParameterizedTest(name = "{0}: installed product uses its own probe protocol")
    @MethodSource("installedProducts")
    void allInstalledProductsUseActualProbeInsteadOfQueryValidationWrapper(ProductTypeEnum product) throws Exception {
        var database = DbSwitchProductRegistry.discover().metadata(product);
        var connection = mock(Connection.class);
        if (product == ProductTypeEnum.MONGODB) {
            var session = mock(MongoNativeSession.class);
            byte[] command = {1}, response = {2};
            try (var factory = mockStatic(MongoNativeSession.class)) {
                factory.when(() -> MongoNativeSession.open(connection, 64 * 1024, 3)).thenReturn(session);
                when(session.encode("{\"ping\":1,\"maxTimeMS\":3000}")).thenReturn(command);
                when(session.command("admin", command)).thenReturn(response);
                when(session.canonicalJson(response)).thenReturn("{\"ok\":1}");
                database.probeConnection(connection, 3);
                verify(session).command("admin", command);
                verify(connection, never()).createStatement();
            }
        } else if (product == ProductTypeEnum.ELASTICSEARCH) {
            var session = mock(ElasticsearchNativeSession.class);
            try (var factory = mockStatic(ElasticsearchNativeSession.class)) {
                factory.when(() -> ElasticsearchNativeSession.open(connection, 64 * 1024, 3)).thenReturn(session);
                when(session.request("GET", "/", null)).thenReturn(new ElasticsearchNativeSession.Response(200,
                        new ObjectMapper().readTree("{\"cluster_name\":\"fixture\",\"version\":{\"number\":\"7.17.0\"}}")));
                database.probeConnection(connection, 3);
                verify(session).request("GET", "/", null);
                verify(connection, never()).createStatement();
            }
        } else {
            String expected = product.getTestSql();
            if (product == ProductTypeEnum.OCEANBASE) {
                mode(connection, "MYSQL"); expected = ProductTypeEnum.MYSQL.getTestSql();
            }
            var statement = sqlReply(connection, expected, true);
            database.probeConnection(connection, 3);
            verify(statement).executeQuery(expected);
            verify(statement).setQueryTimeout(3);
            verify(statement).close();
            verify(connection, never()).isValid(anyInt());
        }
        verify(connection, never()).close();
    }

    @Test void oceanbaseChecksEachPhysicalConnectionAndUsesOracleDualWhenRequired() throws Exception {
        var database = new DatabaseOceanbaseImpl();
        var mysql = mock(Connection.class); mode(mysql, "MYSQL");
        sqlReply(mysql, ProductTypeEnum.MYSQL.getTestSql(), true);
        database.probeConnection(mysql, 2);
        var oracle = mock(Connection.class); mode(oracle, "ORACLE");
        var statement = sqlReply(oracle, ProductTypeEnum.ORACLE.getTestSql(), true);
        database.probeConnection(oracle, 2);
        verify(statement).executeQuery("SELECT 'Hello' from DUAL");
    }

    @Test void oldDriversWithoutStatementTimeoutStillExecuteInsideOuterDeadline() throws Exception {
        var connection = mock(Connection.class);
        var statement = sqlReply(connection, "SELECT 1", true);
        doThrow(new SQLFeatureNotSupportedException()).when(statement).setQueryTimeout(2);
        DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TDENGINE).probeConnection(connection, 2);
        verify(statement).executeQuery("SELECT 1");
    }

    @Test void successfulLocalConnectionStateWithoutServerResponseCannotPass() throws Exception {
        var connection = mock(Connection.class);
        sqlReply(connection, "SELECT 1", false);
        assertThrows(SQLException.class, () -> DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.INFLUXDB3)
                .probeConnection(connection, 2));
    }

    @Test void facadeUsesOnlyMetadataBudgetAndPreservesSqlStateWithoutNetworkTimeoutRequirement() throws Exception {
        var products = mock(DbSwitchProductRegistry.class);
        var dialect = mock(AbstractDatabase.class);
        var metadata = mock(JdbcConnectionProvider.class);
        var workload = mock(JdbcConnectionProvider.class);
        var connection = mock(Connection.class, withSettings().extraInterfaces(DriverNativeAccess.ConnectionAccess.class));
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(dialect);
        when(metadata.open("jdbc:fixture:probe", null, null)).thenReturn(connection);
        when(((DriverNativeAccess.ConnectionAccess) connection).invokeNative(any(), eq(1500L)))
                .thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        doThrow(new SQLException("failed", "08006")).when(dialect).probeConnection(connection, 2);
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, products, metadata, workload);
        var failure = assertThrows(IllegalStateException.class,
                () -> facade.probeConnection("jdbc:fixture:probe", null, null, 1500));
        assertEquals("08006", ((SQLException) failure.getCause()).getSQLState());
        verify(connection).close();
        verify(connection, never()).setNetworkTimeout(any(), anyInt());
        verifyNoInteractions(workload);
    }

    @Test void facadeRejectsUnboundedTimeoutBeforeOpeningConnection() {
        var products = mock(DbSwitchProductRegistry.class);
        var metadata = mock(JdbcConnectionProvider.class);
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(mock(AbstractDatabase.class));
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, products, metadata);
        assertThrows(IllegalArgumentException.class, () -> facade.probeConnection("ignored", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> facade.probeConnection("ignored", null, null, 60001));
        verifyNoInteractions(metadata);
    }

    private static Statement sqlReply(Connection connection, String sql, boolean present) throws SQLException {
        var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(sql)).thenReturn(rows);
        when(rows.next()).thenReturn(present);
        return statement;
    }

    private static void mode(Connection connection, String mode) throws SQLException {
        var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement("show global variables where variable_name = 'ob_compatibility_mode'"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(2)).thenReturn(mode);
    }
}
