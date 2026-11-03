package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@EnabledIfSystemProperty(named="datahub.test.clickhouse.url",matches="jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
class ClickhouseCommentNativeTest {
    @Test void diagnosticNativeCatalogAndCommentEvidenceUseActualServer() throws Exception {
        String table="diag_"+UUID.randomUUID().toString().replace("-", "");
        var database=new DatabaseClickhouseImpl();
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"),"default","");var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE default."+table+" (id Int64 COMMENT 'identifier') ENGINE=MergeTree ORDER BY id COMMENT 'diagnostic table'");
            try {
                database.probeNamespace(connection,"default",5);database.probeAccountPrivileges(connection,"default",5);
                assertTrue(database.queryCatalogTableList(connection,"default",10,5).stream().anyMatch(t->table.equals(t.getTableName())));
                var evidence=database.probeExtendedProperties(connection,"default",table,5);
                assertEquals(1,evidence.columnsRead());assertTrue(evidence.tableCommentPresent());assertEquals(1,evidence.columnCommentsPresent());
            } finally {statement.execute("DROP TABLE IF EXISTS default."+table+" SYNC");}
        }
    }

    @Test void generatedColumnAndTableCommentsRoundTripQuotesBackslashesAndUnicode() throws Exception {
        String table="comment88_"+UUID.randomUUID().toString().replace("-", "");
        String comment="中文 'single' \"double\" \\ path\\notes\nline";
        var database=new DatabaseClickhouseImpl();
        var description=new ColumnDescription();description.setFieldName("id");description.setFieldType(Types.BIGINT);
        description.setPrecisionSize(19);description.setRemarks(comment);
        var sql=new StringBuilder("CREATE TABLE default.").append(table).append("(")
                .append(database.getFieldDefinition(new ColumnMetaData(description),List.of("id"),false,false,true)).append(") ");
        database.postAppendCreateTableSql(sql,comment,List.of("id"),Map.of(),"id");
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"),"default","");var statement=connection.createStatement()) {
            try {
                statement.execute(sql.toString());
                assertEquals(comment,database.queryTableMeta(connection,"default",table).getRemarks());
                assertEquals(comment,database.queryTableColumnMeta(connection,"default",table).getFirst().getRemarks());
            } finally {statement.execute("DROP TABLE IF EXISTS default."+table+" SYNC");}
        }
    }
}
