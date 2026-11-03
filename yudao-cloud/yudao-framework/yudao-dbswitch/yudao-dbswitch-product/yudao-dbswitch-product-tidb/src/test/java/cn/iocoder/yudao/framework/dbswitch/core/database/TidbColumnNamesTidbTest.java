package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exact physical catalog predicates, including JDBC wildcard/quote counterexamples on native TiDB. */
@Timeout(60)
@EnabledIfSystemProperty(named="datahub.test.tidb.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
class TidbColumnNamesTidbTest {
    @ParameterizedTest @ValueSource(strings={"ordinary", "wild_%_name", " back`tick\"name", " 原始,表`\"", "MixedCase"})
    void exactPhysicalNamesNeverExpandAsJdbcPatternsOrDisappearDuringDriverUnquoting(String name) throws Exception {
        String schema="ti73_columns_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(System.getProperty("datahub.test.tidb.url"),System.getProperty("datahub.test.tidb.user","root"),System.getProperty("datahub.test.tidb.password",""))) {
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT TIDB_VERSION()")) {
                assertTrue(rows.next());assertTrue(rows.getString(1).startsWith("Release Version: v8.5.6\n"));assertTrue(rows.getString(1).endsWith("Store: tikv"));
            }
            sql(c,"CREATE DATABASE "+q(schema));
            try {
                sql(c,"CREATE TABLE "+q(schema)+"."+q(name)+"(id BIGINT PRIMARY KEY,label VARCHAR(10),payload VARBINARY(8))",
                        "CREATE TABLE "+q(schema)+"."+q("wild_otherXname")+"(decoy INT)");
                var product=new DatabaseTidbImpl();
                assertEquals(List.of("id","label","payload"),product.queryTableColumnName(c,schema,name));
                assertTrue(product.queryTableColumnName(c,schema,"missing_%_name").isEmpty());
                if (name.equals("MixedCase")) assertTrue(product.queryTableColumnName(c,schema,"mixedcase").isEmpty(),
                        "metadata resolves the exact physical catalog name rather than a case-insensitive alias");
                assertTrue(c.getAutoCommit(),"column metadata must not start or change a transaction");
                assertFalse(c.isClosed(),"caller retains connection ownership");
            } finally {sql(c,"DROP DATABASE IF EXISTS "+q(schema));}
        }
    }
    private static String q(String name){return '`'+name.replace("`","``")+'`';}
    private static void sql(Connection c,String... statements)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(30);for(String text:statements)s.execute(text);}}
}
