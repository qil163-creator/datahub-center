package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 商业实库通过显式环境接入；未配置时跳过，不能当成实库通过。 */
@EnabledIfEnvironmentVariable(named="DBSWITCH_SQLSERVER_TEST_URL", matches=".+")
class SqlserverTemporaryDiagnosticNativeTest {
    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getenv("DBSWITCH_SQLSERVER_TEST_URL"),
                System.getenv("DBSWITCH_SQLSERVER_TEST_USER"), System.getenv("DBSWITCH_SQLSERVER_TEST_PASSWORD"));
    }
    @Test void localTemporaryPlanIsIsolatedTransactionalAndCleaned() throws Exception {
        var plan=new DatabaseSqlserverImpl().temporaryTableDiagnosticPlan("dbswitch_diag_"+UUID.randomUUID().toString().replace("-",""));
        try (var owner=open(); var other=open(); var statement=owner.createStatement(); var observer=other.createStatement()) {
            statement.setQueryTimeout(5); observer.setQueryTimeout(5);
            statement.execute(plan.createSql());
            try {
                statement.executeUpdate("INSERT INTO "+plan.tableReference()+" (probe_id,probe_value) VALUES (1,'owner')");
                assertEquals(208,assertThrows(SQLException.class,()->observer.executeQuery("SELECT COUNT(*) FROM "+plan.tableReference())).getErrorCode());
                owner.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO "+plan.tableReference()+" (probe_id,probe_value) VALUES (9,'rollback')");
                owner.rollback(); owner.setAutoCommit(true);
                try (var rows=statement.executeQuery("SELECT COUNT(*) FROM "+plan.tableReference()+" WHERE probe_id=9")) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
                statement.execute(plan.alterSql());
                try (var ignored=statement.executeQuery("SELECT probe_extra FROM "+plan.tableReference()+" WHERE 1=0")) { assertFalse(ignored.next()); }
                statement.executeUpdate("INSERT INTO "+plan.tableReference()+" (probe_id,probe_value) VALUES (5,'initial')");
                assertEquals(2,statement.executeUpdate(plan.mergeSql()));
                statement.execute(plan.truncateSql());
                try (var rows=statement.executeQuery("SELECT COUNT(*) FROM "+plan.tableReference())) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
            } finally {
                if (!owner.getAutoCommit()) { owner.rollback(); owner.setAutoCommit(true); }
                statement.execute(plan.dropSql());
            }
            assertThrows(SQLException.class,()->statement.executeQuery("SELECT COUNT(*) FROM "+plan.tableReference()));
        }
    }
}
