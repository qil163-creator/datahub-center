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
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public migration entry, task-owned SQL Server 2022 database, exact values and statement-safe names. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
class MigrationSqlServerTest {
    private Connection control;
    private String schema;
    private final Set<String> ownedTables = new LinkedHashSet<>();

    @BeforeEach void setup() throws Exception {
        control=DriverManager.getConnection(url(),"sa",password());
        assertEquals(16,control.getMetaData().getDatabaseMajorVersion());
        schema="migration_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup() throws Exception {
        if(control!=null)try(var owned=control) {
            if(schema!=null) {
                for(String table:ownedTables)sql("DROP TABLE IF EXISTS "+table(table));
                sql("DROP SCHEMA "+q(schema));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullThenDiffPreservesDecimalUnicodeBinaryTimestampAndQuotedNames(boolean quoted)throws Exception {
        String source=quoted?"source]; rows":"source_rows",target=quoted?"target]; rows":"target_rows";
        String note=quoted?"note]; value":"note";
        create(source,columns(note));create(target,columns(note));seed(source);
        var full=run(source,target,false);assertSuccess(full);
        assertEquals(503,full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertEquals(rows(source,note),rows(target,note));
        sql("DELETE FROM "+table(source)+" WHERE id=9007199254740993");
        sql("UPDATE "+table(source)+" SET amount=-0.000000000000000001,"+q(note)+"=N'更新' WHERE id=9007199254740994");
        sql("INSERT INTO "+table(source)+" VALUES(1,9223372036854775807,99999999999999999999.999999999999999999,NULL,0x00FF00,NULL)");
        assertSuccess(run(source,target,true));assertEquals(rows(source,note),rows(target,note));
        assertSuccess(run(source,target,true));assertEquals(rows(source,note),rows(target,note));
    }
    @Test void autoCreateKeepsNativeDecimalAndDatetimePrecision()throws Exception {
        create("source_rows",columns("note"));seed("source_rows");ownedTables.add("created_rows");
        assertSuccess(run("source_rows","created_rows",false));
        assertEquals(rows("source_rows","note"),rows("created_rows","note"));
    }
    @Test void keysOnlyDifferenceSupportsInsertAndDelete()throws Exception {
        create("source_rows","(id BIGINT NOT NULL PRIMARY KEY)");create("target_rows","(id BIGINT NOT NULL PRIMARY KEY)");
        sql("INSERT INTO "+table("source_rows")+" VALUES(1),(3)");sql("INSERT INTO "+table("target_rows")+" VALUES(1),(2)");
        assertSuccess(run("source_rows","target_rows",true));
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT id FROM "+table("target_rows")+" ORDER BY id")) {
            assertTrue(rows.next());assertEquals(1,rows.getLong(1));assertTrue(rows.next());assertEquals(3,rows.getLong(1));assertFalse(rows.next());
        }
    }
    private String columns(String note) {
        return "(part SMALLINT NOT NULL,id BIGINT NOT NULL,amount DECIMAL(38,18),"+q(note)
                +" NVARCHAR(256),payload VARBINARY(16),event_time DATETIME2(7),PRIMARY KEY(part,id))";
    }
    private void seed(String table)throws Exception {
        try(var statement=control.prepareStatement("INSERT INTO "+table(table)+" VALUES(?,?,?,?,?,?)")) {
            for(int i=0;i<503;i++) {
                statement.setInt(1,i%2);statement.setLong(2,9007199254740993L+i);
                statement.setBigDecimal(3,i%3==0?null:new BigDecimal("12345678901234567890.123456789012345678"));
                statement.setNString(4,i%3==0?null:"中文'\\\n😀"+i);statement.setBytes(5,new byte[]{0,(byte)i,(byte)255});
                statement.setTimestamp(6,Timestamp.valueOf("2026-10-03 12:34:56.1234567"));statement.addBatch();
            }
            statement.executeBatch();
        }
    }
    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.SQLSERVER);source.setUrl(url());source.setUsername("sa");source.setPassword(password());
        source.setSourceSchema(schema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceTable)+"$",targetTable)));
        request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.SQLSERVER);target.setUrl(url());target.setUsername("sa");target.setPassword(password());
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()) {return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private List<List<Object>> rows(String table,String note)throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT part,id,amount,"+q(note)+",payload,event_time FROM "+table(table)+" ORDER BY part,id")) {
            while(rows.next())result.add(Arrays.asList(rows.getInt(1),rows.getLong(2),rows.getBigDecimal(3),rows.getNString(4),
                    rows.getBytes(5)==null?null:HexFormat.of().formatHex(rows.getBytes(5)),rows.getTimestamp(6)));
        }
        return result;
    }
    private void create(String table,String columns)throws Exception {ownedTables.add(table);sql("CREATE TABLE "+table(table)+columns);}
    private void sql(String text)throws Exception {try(var statement=control.createStatement()){statement.setQueryTimeout(20);statement.execute(text);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return '['+name.replace("]","]]")+']';}
    private static String url(){return System.getProperty("datahub.test.sqlserver.url");}
    private static String password(){return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD");}
    private static void assertSuccess(DbSwitchResult result) {
        assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
    }
}
