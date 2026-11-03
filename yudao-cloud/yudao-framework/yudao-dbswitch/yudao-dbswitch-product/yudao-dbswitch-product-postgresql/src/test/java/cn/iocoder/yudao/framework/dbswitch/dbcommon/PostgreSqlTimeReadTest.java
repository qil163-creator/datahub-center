package cn.iocoder.yudao.framework.dbswitch.dbcommon;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcTimeValues;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.PostgreSqlDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL 16/18 and pgjdbc 42.7.13: ordinary routes and actual prepared text/binary TIME carriers. */
@Timeout(240)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgreSqlTimeReadTest {
    private final String schema = "ptime_" + UUID.randomUUID().toString().replace("-", "");
    private Connection postgres;
    @AfterEach void cleanup() throws Exception {
        if (postgres != null) try (var c = postgres) { sql(c, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); }
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
    @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void postgresOrdinaryReadRoutesKeepMicrosecondsAndRejectTheUnrepresentableEndOfDay(int route) throws Exception {
        Class.forName("org.postgresql.Driver");
        postgres = DriverManager.getConnection(pgUrl(), System.getProperty("datahub.test.postgres.user", "dataforge_test"), System.getProperty("datahub.test.postgres.password", ""));
        assertTrue(Set.of(16,18).contains(postgres.getMetaData().getDatabaseMajorVersion()));
        sql(postgres, "CREATE SCHEMA \"" + schema + "\"");
        sql(postgres, "CREATE TABLE \"" + schema + "\".src(k bigint NOT NULL PRIMARY KEY,v time(6))");
        sql(postgres, "INSERT INTO \"" + schema + "\".src VALUES(1,'23:59:59.123456'),(2,NULL),(3,'24:00:00')");
        var operator = new PostgreSqlDatabaseOperator(new org.springframework.jdbc.datasource.DriverManagerDataSource(pgUrl(),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), System.getProperty("datahub.test.postgres.password", "")));
        String select = "SELECT k,v FROM \"" + schema + "\".src ORDER BY k";
        var resource = readRoute(operator, route, select);
        try (resource) {
            var rows = resource.getResultset(); var seen = new HashSet<Long>();
            while (rows.next()) {
                long id = rows.getLong(1); assertTrue(seen.add(id));
                if (id == 3) {
                    var rejection = assertThrows(SQLException.class, () -> rows.getObject("v"));
                    assertTrue(rejection.getMessage().contains("24:"), rejection.getMessage());
                } else {
                    LocalTime expected = id == 1 ? LocalTime.parse("23:59:59.123456") : null;
                    assertEquals(expected, rows.getObject("v")); assertEquals(expected, rows.getObject(2));
                }
            }
            assertEquals(Set.of(1L, 2L, 3L), seen);
        }
        assertTrue(resource.getResultset().isClosed()); assertTrue(resource.getStatement().isClosed()); assertTrue(resource.getConnection().isClosed());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void strictClockCarrierKeepsHighestLegalTimeAndRejectsEndOfDayInTextAndBinaryProtocols(boolean binary) throws Exception {
        Class.forName("org.postgresql.Driver");
        String selectedUrl = pgUrl() + (pgUrl().contains("?") ? "&" : "?") + "binaryTransfer=true&binaryTransferEnable=time";
        for (String text : Arrays.asList("23:59:59.123456", "23:59:59.999999", null, "24:00:00")) {
            var resource = new StatementResultSet();
            try (resource) {
                var connection = DriverManager.getConnection(selectedUrl, System.getProperty("datahub.test.postgres.user", "dataforge_test"),
                        System.getProperty("datahub.test.postgres.password", ""));
                resource.setConnection(connection); resource.setAutoCommit(connection.getAutoCommit());
                var statement = connection.prepareStatement("SELECT ?::time(6) AS v"); resource.setStatement(statement);
                statement.unwrap(org.postgresql.PGStatement.class).setPrepareThreshold(binary ? -1 : 0);
                statement.setString(1, text); var raw = statement.executeQuery(); resource.setResultset(raw);
                assertTrue(raw.next());
                var fieldMember = raw.getClass().getDeclaredField("fields"); fieldMember.setAccessible(true);
                Object field = ((Object[]) fieldMember.get(raw))[0];
                assertEquals(binary ? 1 : 0, field.getClass().getMethod("getFormat").invoke(field), "actual wire format");
                var wrapped = JdbcTimeValues.losslessMicrosecondTimeRead(resource).getResultset();
                if ("24:00:00".equals(text)) {
                    assertEquals(LocalTime.MAX, raw.getObject(1, LocalTime.class), "actual driver end-of-day carrier");
                    assertThrows(SQLException.class, () -> wrapped.getObject(1));
                    assertThrows(SQLException.class, () -> wrapped.getObject("v", LocalTime.class));
                } else {
                    LocalTime expected = text == null ? null : LocalTime.parse(text);
                    assertEquals(expected, wrapped.getObject(1)); assertEquals(expected, wrapped.getObject("v", LocalTime.class));
                }
            }
            assertTrue(resource.getResultset().isClosed()); assertTrue(resource.getStatement().isClosed()); assertTrue(resource.getConnection().isClosed());
        }
    }

    private StatementResultSet readRoute(PostgreSqlDatabaseOperator operator, int route, String select) {
        return switch (route) {
            case 0 -> operator.queryTableData(select);
            case 1 -> operator.queryTableData(select, List.of("k", "v"));
            case 2 -> operator.queryTableData(select, List.of("k", "v"), List.of("k"));
            case 3 -> operator.queryTableData(schema, "src", List.of("k", "v"));
            case 4 -> operator.queryTableData(schema, "src", List.of("k", "v"), List.of("k"));
            default -> throw new IllegalArgumentException("Unknown native read route");
        };
    }
    private static void sql(Connection connection, String query) throws SQLException { try (var s = connection.createStatement()) { s.setQueryTimeout(30); s.execute(query); } }
    private static String pgUrl() { return System.getProperty("datahub.test.postgres.url"); }
}
