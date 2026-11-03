package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual TiDB SQL/PD/TiKV fixture, not a MySQL substitution. Uses only randomly named owned schemas. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.tidb.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
class MigrationTidbTest {
    private String schema;
    private Connection control;

    @BeforeEach void setup() throws Exception {
        control=DriverManager.getConnection(url(),user(),"");
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT tidb_version()")) {
            assertTrue(rows.next());assertTrue(rows.getString(1).contains("Release Version:"));
        }
        schema="tidb_migration_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE "+q(schema));
    }
    @AfterEach void cleanup() throws Exception {
        if(control!=null)try(var owned=control) {if(schema!=null)sql("DROP DATABASE IF EXISTS "+q(schema));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullThenDifferenceKeepCompositeKeysUnsignedDecimalBinaryAndQuotedNames(boolean quoted) throws Exception {
        String source=quoted?"source` rows":"source_rows",target=quoted?"target` rows":"target_rows";
        String note=quoted?"note` value":"note";
        String columns="(part SMALLINT NOT NULL,id BIGINT UNSIGNED NOT NULL,amount DECIMAL(65,30),"
                +q(note)+" VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin,payload VARBINARY(16),event_time DATETIME(6),PRIMARY KEY(part,id))";
        sql("CREATE TABLE "+table(source)+columns);sql("CREATE TABLE "+table(target)+columns);
        seed(source);
        var full=run(source,target,false);assertSuccess(full);
        assertEquals(503,full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertEquals(rows(source,note),rows(target,note));
        sql("DELETE FROM "+table(source)+" WHERE part=0 AND id=9007199254740993");
        sql("UPDATE "+table(source)+" SET amount=-0.000000000000000000000000000001,"+q(note)+"='changed' WHERE part=1 AND id=9007199254740994");
        sql("INSERT INTO "+table(source)+" VALUES(1,18446744073709551615,99999999999999999999999999999999999.999999999999999999999999999999,NULL,X'00FF00',NULL)");
        assertSuccess(run(source,target,true));assertEquals(rows(source,note),rows(target,note));
        assertSuccess(run(source,target,true));assertEquals(rows(source,note),rows(target,note));
    }

    @Test void publicEntryCreatesMissingTargetWithExactNativeNumericColumns() throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(id BIGINT UNSIGNED PRIMARY KEY,amount DECIMAL(65,30),note VARCHAR(256),payload VARBINARY(16),event_time DATETIME(6),part SMALLINT)");
        sql("INSERT INTO "+table("source_rows")+" VALUES(18446744073709551615,12345678901234567890123456789012345.123456789012345678901234567890,NULL,X'00FF','2026-10-03 12:34:56.123456',1)");
        assertSuccess(run("source_rows","created_rows",false));
        assertEquals(rows("source_rows","note"),rows("created_rows","note"));
        try(var statement=control.prepareStatement("SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema=? AND table_name='created_rows' AND column_name='id'")) {
            statement.setString(1,schema);try(var result=statement.executeQuery()) {assertTrue(result.next());assertEquals("bigint unsigned",result.getString(1).toLowerCase(Locale.ROOT));}
        }
    }

    private void seed(String table) throws Exception {
        try(var statement=control.prepareStatement("INSERT INTO "+table(table)+" VALUES(?,?,?,?,?,?)")) {
            for(int i=0;i<503;i++) {
                statement.setInt(1,i%2);statement.setBigDecimal(2,new BigDecimal("9007199254740993").add(BigDecimal.valueOf(i)));
                statement.setBigDecimal(3,i%3==0?null:new BigDecimal("12345678901234567890123456789012345.123456789012345678901234567890"));
                statement.setString(4,i%3==0?null:"中文'\\\n😀"+i);statement.setBytes(5,new byte[]{0,(byte)i,(byte)255});
                statement.setTimestamp(6,Timestamp.valueOf(LocalDateTime.of(2026,10,3,12,34,56,123456000)));statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    @Test void publicMigrationExecutesPlannedBeforeAndAfterHooks() throws Exception {
        for(String name:List.of("source_rows","target_rows"))sql("CREATE TABLE "+table(name)+"(id INT PRIMARY KEY)");
        sql("CREATE TABLE "+table("hook_audit")+"(phase INT PRIMARY KEY,row_count INT)");
        sql("INSERT INTO "+table("source_rows")+" VALUES(1),(2)");
        String before="INSERT INTO "+table("hook_audit")+" SELECT 1,COUNT(*) FROM "+table("target_rows");
        String after="INSERT INTO "+table("hook_audit")+" SELECT 2,COUNT(*) FROM "+table("target_rows");
        assertSuccess(run("source_rows","target_rows",false,before,after));
        try(var s=control.createStatement();var r=s.executeQuery("SELECT phase,row_count FROM "+table("hook_audit")+" ORDER BY phase")) {
            assertTrue(r.next());assertEquals(1,r.getInt(1));assertEquals(0,r.getInt(2));
            assertTrue(r.next());assertEquals(2,r.getInt(1));assertEquals(2,r.getInt(2));assertFalse(r.next());
        }
    }

    @Test void invalidHookTailCannotExecuteItsValidWritePrefix() throws Exception {
        for(String name:List.of("source_rows","target_rows"))sql("CREATE TABLE "+table(name)+"(id INT PRIMARY KEY)");
        String before="INSERT INTO "+table("target_rows")+" VALUES(77); SELECT '";
        var failure=assertThrows(MigrationService.ScriptExecutionException.class,()->run("source_rows","target_rows",false,before,null));
        assertEquals(MigrationService.ScriptState.REJECTED,failure.execution().state());
        assertEquals(0,failure.execution().acknowledgedStatements());
        try(var s=control.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table("target_rows"))) {
            assertTrue(r.next());assertEquals(0,r.getInt(1));
        }
    }

    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff) throws Exception {
        return run(sourceTable,targetTable,diff,null,null);
    }
    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff,String before,String after) throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.TIDB);source.setUrl(url());source.setUsername(user());source.setPassword("");
        source.setSourceSchema(schema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceTable)+"$",targetTable)));
        request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.TIDB);target.setUrl(url());target.setUsername(user());target.setPassword("");
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        target.setBeforeSqlScripts(before);target.setAfterSqlScripts(after);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()) {return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private List<List<Object>> rows(String table,String note) throws Exception {
        var values=new ArrayList<List<Object>>();
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT part,id,amount,"+q(note)+",payload,event_time FROM "+table(table)+" ORDER BY part,id")) {
            while(rows.next())values.add(Arrays.asList(rows.getInt(1),rows.getBigDecimal(2),rows.getBigDecimal(3),rows.getString(4),
                    rows.getBytes(5)==null?null:HexFormat.of().formatHex(rows.getBytes(5)),rows.getTimestamp(6)));
        }
        return values;
    }
    private static void assertSuccess(DbSwitchResult result) {
        assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
    }
    private void sql(String sql)throws Exception {try(var statement=control.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return '`'+name.replace("`","``")+'`';}
    private static String url(){return System.getProperty("datahub.test.tidb.url");}
    private static String user(){return System.getProperty("datahub.test.tidb.user","root");}
}
