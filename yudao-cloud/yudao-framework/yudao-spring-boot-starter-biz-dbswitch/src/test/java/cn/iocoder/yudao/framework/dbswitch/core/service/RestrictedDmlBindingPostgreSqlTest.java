package cn.iocoder.yudao.framework.dbswitch.core.service;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
@Testcontainers(disabledWithoutDocker = true)
class RestrictedDmlBindingPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private final AbstractDatabase database=DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
    @Test void rejectsCrossSchemaAndPreservesQuotedCase() {
        assertThrows(IllegalArgumentException.class, () -> bind("UPDATE schema_b.citizen SET x=1", "schema_a", "citizen"));
        assertThrows(IllegalArgumentException.class, () -> bind("UPDATE schema_a.citizen SET x=(SELECT max(x) FROM schema_b.citizen)", "schema_a", "citizen"));
        assertThrows(IllegalArgumentException.class, () -> bind("UPDATE \"Case\".citizen SET x=1", "Case", "Citizen"));
        assertThrows(IllegalArgumentException.class, () -> bind("UPDATE case.\"Citizen\" SET x=1", "Case", "Citizen"));
        assertTrue(bind("UPDATE \"Case\".\"Citizen\" SET x=1", "Case", "Citizen").contains("\"Case\".\"Citizen\""));
    }
    @Test void fullyQualifiedBindingDefeatsSearchPathAndTemporaryShadow() throws Exception {
        try(Connection c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            Statement s=c.createStatement()) {
            s.execute("CREATE SCHEMA schema_a;CREATE SCHEMA schema_b;CREATE TABLE schema_a.citizen(id int,x int);CREATE TABLE schema_b.citizen(id int,x int)");
            s.execute("INSERT INTO schema_a.citizen VALUES(1,0);INSERT INTO schema_b.citizen VALUES(1,0);CREATE TEMP TABLE citizen(id int,x int);INSERT INTO citizen VALUES(1,0)");
            c.setSchema("schema_b");
            assertEquals(1,s.executeUpdate(bind("UPDATE citizen SET x=7 WHERE id=1", "schema_a","citizen")));
            assertEquals(7,value(s,"schema_a.citizen"));assertEquals(0,value(s,"schema_b.citizen"));assertEquals(0,value(s,"pg_temp.citizen"));
            s.execute("CREATE SCHEMA \"Case\";CREATE TABLE \"Case\".\"Citizen\"(id int,x int);INSERT INTO \"Case\".\"Citizen\" VALUES(1,0)");
            assertEquals(1,s.executeUpdate(bind("UPDATE \"Citizen\" SET x=9", "Case","Citizen")));
            assertEquals(9,value(s,"\"Case\".\"Citizen\""));
        }
    }
    private String bind(String sql,String schema,String table) {
        return RestrictedDmlGuard.bindAuthorizedTable(database,sql,schema,table).getNormalizedSql();
    }
    private int value(Statement s,String table) throws SQLException {
        try(ResultSet r=s.executeQuery("SELECT x FROM "+table)) {r.next();return r.getInt(1);}
    }
}
