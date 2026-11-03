package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 旧产品须独立接入验证，不借现代 SQL Server 的实例结果声称通过。 */
@EnabledIfEnvironmentVariable(named="DBSWITCH_SQLSERVER2000_TEST_URL",matches=".+")
class Sqlserver2000TemporaryDiagnosticNativeTest {
    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getenv("DBSWITCH_SQLSERVER2000_TEST_URL"),
                System.getenv("DBSWITCH_SQLSERVER2000_TEST_USER"),System.getenv("DBSWITCH_SQLSERVER2000_TEST_PASSWORD"));
    }
    @Test void localTemporaryPlanKeepsDefinitionAndRowsSessionLocalAndRollsBack() throws Exception {
        var plan=new DatabaseSqlserver2000Impl().temporaryTableDiagnosticPlan("dbswitch_diag_"+UUID.randomUUID().toString().replace("-",""));
        try (var owner=open(); var other=open(); var statement=owner.createStatement(); var observer=other.createStatement()) {
            assertEquals(8,owner.getMetaData().getDatabaseMajorVersion());
            statement.setQueryTimeout(5); observer.setQueryTimeout(5); statement.execute(plan.createSql());
            try {
                statement.executeUpdate("INSERT INTO "+plan.tableReference()+" (probe_id,probe_value) VALUES (1,'owner')");
                assertEquals(208,assertThrows(SQLException.class,()->observer.executeQuery("SELECT COUNT(*) FROM "+plan.tableReference())).getErrorCode());
                owner.setAutoCommit(false);
                statement.executeUpdate("INSERT INTO "+plan.tableReference()+" (probe_id,probe_value) VALUES (9,'rollback')");
                owner.rollback(); owner.setAutoCommit(true);
                try (var rows=statement.executeQuery("SELECT COUNT(*) FROM "+plan.tableReference()+" WHERE probe_id=9")) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
                try (var batch=owner.prepareStatement("INSERT INTO "+plan.tableReference()+" (probe_id,probe_value) VALUES (?,?)")) {
                    batch.setQueryTimeout(5);
                    for (int id=2;id<=3;id++) { batch.setInt(1,id); batch.setString(2,"batch"); batch.addBatch(); }
                    assertEquals(2,batch.executeBatch().length);
                }
                statement.execute(plan.alterSql());
                try (var rows=statement.executeQuery("SELECT probe_extra FROM "+plan.tableReference()+" WHERE 1=0")) { assertFalse(rows.next()); }
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
