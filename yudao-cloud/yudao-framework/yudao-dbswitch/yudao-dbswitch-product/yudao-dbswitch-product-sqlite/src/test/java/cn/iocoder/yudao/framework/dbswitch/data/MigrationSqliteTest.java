package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real file-backed SQLite; full public service and separate source/target physical files. */
@Timeout(45)
class MigrationSqliteTest {
    @TempDir Path directory;
    String sourceUrl, targetUrl;
    @BeforeEach void setup() {
        sourceUrl = "jdbc:sqlite:" + directory.resolve("source.db");
        targetUrl = "jdbc:sqlite:" + directory.resolve("target.db");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullThenDiffPreserveIntegerBlobNullAndQuotedPhysicalNames(boolean quoted) throws Exception {
        String source = quoted ? "source\"; rows" : "source_rows";
        String target = quoted ? "target\"; rows" : "target_rows";
        String note = quoted ? "note\"; value" : "note";
        String columns = "(id INTEGER PRIMARY KEY," + q(note) + " TEXT, payload BLOB)";
        sql(sourceUrl, "CREATE TABLE " + q(source) + columns);
        sql(targetUrl, "CREATE TABLE " + q(target) + columns);
        try (var connection=DriverManager.getConnection(sourceUrl);
             var insert=connection.prepareStatement("INSERT INTO " + q(source) + " VALUES(?,?,?)")) {
            for (int i=0;i<503;i++) {
                insert.setLong(1,9007199254740993L+i);
                insert.setString(2,i%3==0?null:"中文'\\\n😀"+i);
                insert.setBytes(3,new byte[]{0,(byte)i,(byte)255}); insert.addBatch();
            }
            insert.executeBatch();
        }
        var full=run(source,target,false);
        assertEquals(1,full.getTableResultList().size());
        assertTrue(full.getIfAllSuccess().get(),()->full.getTableResultList().toString());
        assertEquals(503,full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertRowsEqual(source,target,note);
        sql(sourceUrl,"DELETE FROM "+q(source)+" WHERE id=9007199254740993");
        sql(sourceUrl,"UPDATE "+q(source)+" SET "+q(note)+"='changed' WHERE id=9007199254740994");
        sql(sourceUrl,"INSERT INTO "+q(source)+" VALUES(9223372036854775807,NULL,X'00FF00')");
        var diff=run(source,target,true);
        assertTrue(diff.getIfAllSuccess().get(),()->diff.getTableResultList().toString());
        assertRowsEqual(source,target,note);
        assertTrue(run(source,target,true).getIfAllSuccess().get());
        assertRowsEqual(source,target,note);
    }

    @Test void autoCreateRunsThroughPublicMigrationEntry() throws Exception {
        sql(sourceUrl,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY,note TEXT)");
        sql(sourceUrl,"INSERT INTO source_rows VALUES(9007199254740993,'copied'),(9223372036854775807,NULL)");
        var result=run("source_rows","target_rows",false);
        assertEquals(1,result.getTableResultList().size());
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        try(var c=DriverManager.getConnection(targetUrl);var s=c.createStatement();var rows=s.executeQuery("SELECT id,note FROM target_rows ORDER BY id")) {
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));assertEquals("copied",rows.getString(2));
            assertTrue(rows.next());assertEquals(Long.MAX_VALUE,rows.getLong(1));assertNull(rows.getString(2));assertFalse(rows.next());
        }
    }

    @Test void keysOnlyDifferenceStillSupportsInsertAndDelete() throws Exception {
        sql(sourceUrl,"CREATE TABLE source_rows(id INTEGER PRIMARY KEY)");
        sql(targetUrl,"CREATE TABLE target_rows(id INTEGER PRIMARY KEY)");
        sql(sourceUrl,"INSERT INTO source_rows VALUES(1),(3)");sql(targetUrl,"INSERT INTO target_rows VALUES(1),(2)");
        var result=run("source_rows","target_rows",true);
        assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get());
        try(var c=DriverManager.getConnection(targetUrl);var s=c.createStatement();var rows=s.executeQuery("SELECT id FROM target_rows ORDER BY id")) {
            assertTrue(rows.next());assertEquals(1,rows.getInt(1));assertTrue(rows.next());assertEquals(3,rows.getInt(1));assertFalse(rows.next());
        }
    }

    @Test void clearingAttachedTablePreservesBothIdentityHighWaterMarks() throws Exception {
        try(var c=DriverManager.getConnection(targetUrl);var s=c.createStatement()) {
            try(var attach=c.prepareStatement("ATTACH DATABASE ? AS archive")) {
                attach.setString(1,directory.resolve("archive.db").toString());attach.execute();
            }
            s.execute("CREATE TABLE main.rows(id INTEGER PRIMARY KEY AUTOINCREMENT,note TEXT)");
            s.execute("CREATE TABLE archive.rows(id INTEGER PRIMARY KEY AUTOINCREMENT,note TEXT)");
            s.execute("INSERT INTO main.rows VALUES(50,'issued')");s.execute("DELETE FROM main.rows");
            s.execute("INSERT INTO archive.rows VALUES(100,'issued')");
            var source=new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true);
            new cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.SqliteDatabaseOperator(source).truncateTableData("archive","rows");
            s.execute("INSERT INTO main.rows(note) VALUES('next')");s.execute("INSERT INTO archive.rows(note) VALUES('next')");
            try(var rows=s.executeQuery("SELECT (SELECT id FROM main.rows),(SELECT id FROM archive.rows)")) {
                assertTrue(rows.next());assertEquals(51,rows.getLong(1));assertEquals(101,rows.getLong(2));
            }
        }
    }

    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff) throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.SQLITE3);source.setUrl(sourceUrl);source.setSourceSchema("main");
        source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceTable)+"$",targetTable)));
        request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.SQLITE3);target.setUrl(targetUrl);
        target.setTargetSchema("main");target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()) {return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private void assertRowsEqual(String source,String target,String note) throws Exception {
        assertEquals(rows(sourceUrl,source,note),rows(targetUrl,target,note));
    }
    private List<List<Object>> rows(String url,String table,String note) throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var c=DriverManager.getConnection(url);var s=c.createStatement();var rows=s.executeQuery("SELECT id,"+q(note)+",payload FROM "+q(table)+" ORDER BY id")) {
            while(rows.next())result.add(Arrays.asList(rows.getLong(1),rows.getString(2),HexFormat.of().formatHex(rows.getBytes(3))));
        }
        return result;
    }
    private static String q(String identifier){return "\""+identifier.replace("\"","\"\"")+"\"";}
    private static void sql(String url,String sql)throws Exception {
        try(var c=DriverManager.getConnection(url);var s=c.createStatement()){s.execute(sql);}
    }
}
