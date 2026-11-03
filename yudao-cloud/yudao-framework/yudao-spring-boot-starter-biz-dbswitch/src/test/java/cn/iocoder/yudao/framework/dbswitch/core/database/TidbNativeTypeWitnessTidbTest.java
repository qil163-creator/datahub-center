package cn.iocoder.yudao.framework.dbswitch.core.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class TidbNativeTypeWitnessTidbTest {
    @Test void scalarEmptySubqueryPreservesOriginalEnumAndSetWithoutPayload() throws Exception {
        String schema = "tidb_witness_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"), "root", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(10); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(v VARCHAR(100),e ENUM('a','b'),s SET('a','b'),c CHAR(10),i SMALLINT,t TEXT)");
                sql.execute("INSERT INTO " + schema + ".sample VALUES('a','a','a','a',1,'a')");
                for (String column : List.of("v", "e", "s", "c", "i", "t")) {
                    try (var rows = sql.executeQuery("SELECT k." + column + ", (SELECT k." + column
                            + " WHERE FALSE) FROM " + schema + ".sample k")) {
                        var meta = rows.getMetaData();
                        // Connector/J reports native TiDB ENUM/SET as CHAR, not VARCHAR.
                        assertEquals(java.util.Map.of("v", "VARCHAR", "e", "CHAR", "s", "CHAR", "c", "CHAR", "i", "SMALLINT", "t", "MEDIUMTEXT").get(column), meta.getColumnTypeName(1));
                        assertEquals(meta.getColumnTypeName(1), meta.getColumnTypeName(2), column);
                        assertEquals(meta.getColumnType(1), meta.getColumnType(2), column);
                        assertTrue(rows.next()); assertNull(rows.getObject(2)); assertFalse(rows.next());
                    }
                }
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
}
