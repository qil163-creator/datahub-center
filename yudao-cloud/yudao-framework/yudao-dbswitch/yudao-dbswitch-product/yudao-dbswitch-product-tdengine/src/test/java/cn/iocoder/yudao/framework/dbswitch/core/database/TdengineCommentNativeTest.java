package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@EnabledIfSystemProperty(named="datahub.test.tdengine.ws.url",matches="jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfEnvironmentVariable(named="DBSWITCH_TEST_TDENGINE_PASSWORD",matches=".+")
class TdengineCommentNativeTest {
    @ParameterizedTest
    @ValueSource(strings={"中文 'single' \\ path\\notes\nline", "中文 \"double\" \\ path\\notes\nline", "中文 both trailing\\\\"})
    void legacyTableCommentUsesTheNativeEscapingAndRoundTripsExactly(String comment) throws Exception {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        String schema="comment88_"+UUID.randomUUID().toString().replace("-", "");
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.tdengine.ws.url"),"root",System.getenv("DBSWITCH_TEST_TDENGINE_PASSWORD"));var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+schema+" VGROUPS 1 BUFFER 8");
            try {
                var sql=new StringBuilder("CREATE TABLE "+schema+".sample(ts TIMESTAMP,id INT)");
                new DatabaseTdengineImpl().postAppendCreateTableSql(sql,comment,List.of(),Map.of(),"ts");
                statement.execute(sql.toString());
                try(var rows=statement.executeQuery("SELECT table_comment FROM information_schema.ins_tables WHERE db_name='"+schema+"' AND table_name='sample'")) {
                    assertTrue(rows.next());assertEquals(comment,rows.getString(1));assertFalse(rows.next());
                }
            } finally {statement.execute("DROP DATABASE "+schema);}
        }
    }
    @Test void unrepresentableRawCommentCannotProduceChangedTextOrAnOpenLiteral() {
        for(String comment:List.of("both 'single' and \"double\"", "trailing\\", "nul\0value"))
            assertThrows(IllegalArgumentException.class,()->cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineNativeTables.commentLiteral(comment));
    }
}
