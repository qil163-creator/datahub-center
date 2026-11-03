package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import com.zaxxer.hikari.HikariDataSource;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class PostgresCopyIdentifierPostgreSqlTest {
    @Test void realCopyRoundTripsLiteralQuotesInSchemaTableAndColumns()throws Exception {
        String url=System.getProperty("datahub.test.postgres.url"),user=System.getProperty("datahub.test.postgres.user","dataforge_test");
        String schema="copy\"_"+UUID.randomUUID().toString().replace("-","");
        String table="\"literal table\"",id="id\" field",note="\"note\"";
        String relation=q(schema)+'.'+q(table);
        try(var control=DriverManager.getConnection(url,user,"");var pool=new HikariDataSource()) {
            pool.setJdbcUrl(url);pool.setUsername(user);pool.setMaximumPoolSize(1);pool.setMinimumIdle(0);
            try(var sql=control.createStatement()) {
                sql.execute("CREATE SCHEMA "+q(schema));
                try {
                    sql.execute("CREATE TABLE "+relation+" ("+q(id)+" BIGINT,"+q(note)+" TEXT)");
                    var writer=new PostgresCopyWriterImpl(pool);var fields=List.of(id,note);writer.prepareWrite(schema,table,fields);
                    assertEquals(2,writer.write(fields,List.of(new Object[]{9007199254740993L,"中文'\\value"},new Object[]{2L,null})));
                    try(var rows=sql.executeQuery("SELECT "+q(id)+','+q(note)+" FROM "+relation+" ORDER BY "+q(id))) {
                        assertTrue(rows.next());assertEquals(2L,rows.getLong(1));assertNull(rows.getString(2));
                        assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));assertEquals("中文'\\value",rows.getString(2));assertFalse(rows.next());
                    }
                }finally{sql.execute("DROP SCHEMA "+q(schema)+" CASCADE");}
            }
        }
    }
    private static String q(String name){return PostgreSqlUtils.quoteIdentifier(name);}
}
