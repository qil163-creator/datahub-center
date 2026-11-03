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
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleNativeTables;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

/** Public FULL/DIFF against a disposable schema in the task-owned Oracle Free PDB. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.oracle.url",matches="jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
class MigrationOracleTest {
    private Connection control;
    private String schema, userPassword;
    @BeforeEach void setup() throws Exception {
        Class.forName("oracle.jdbc.OracleDriver");
        control=DriverManager.getConnection(url(),"SYSTEM",System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));
        assertTrue(control.getMetaData().getDatabaseProductName().contains("Oracle"));
        schema="MIG_"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
        userPassword="Qx"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE USER "+q(schema)+" IDENTIFIED BY "+userPassword+" DEFAULT TABLESPACE CODEX_Q11 QUOTA 100M ON CODEX_Q11");
        sql("GRANT CREATE SESSION, CREATE TABLE TO "+q(schema));
    }
    @AfterEach void cleanup() throws Exception {
        if(control!=null)try(var owned=control) { if(schema!=null)sql("DROP USER "+q(schema)+" CASCADE"); }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullThenDiffPreservesExactNumbersLobsNanosAndQuotedNames(boolean quoted)throws Exception {
        String source=quoted?"source'; rows":"source_rows",target=quoted?"target'; rows":"target_rows";
        String note=quoted?"note'; value":"note";
        create(source,columns(note));create(target,columns(note));seed(source,note);
        var full=run(source,target,false);assertSuccess(full);
        assertEquals(503,full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertRowsEqual(source,target,note);
        sql("DELETE FROM "+table(source)+" WHERE \"id\"=9007199254740993");
        sql("UPDATE "+table(source)+" SET \"amount\"=-0.000000000000000001,"+q(note)+"=N'更新' WHERE \"id\"=9007199254740994");
        sql("INSERT INTO "+table(source)+" (\"part\",\"id\",\"amount\") VALUES(1,9223372036854775807,99999999999999999999.999999999999999999)");
        assertSuccess(run(source,target,true));assertRowsEqual(source,target,note);
        assertSuccess(run(source,target,true));assertRowsEqual(source,target,note);
    }
    @Test void autoCreatePreservesNumericAndTimestampScale()throws Exception {
        create("source_rows",columns("note"));seed("source_rows","note");
        assertSuccess(run("source_rows","created_rows",false));
        assertRowsEqual("source_rows","created_rows","note");
        assertEquals(catalog("source_rows"),catalog("created_rows"));
        assertEquals(primaryKey("source_rows"),primaryKey("created_rows"));
    }
    @Test void onlyCreatePreservesNativeDomainsLengthSemanticsAndComments()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) PRIMARY KEY,\"negative\" NUMBER(5,-2),\"fraction\" NUMBER(3,5),"
                +"\"unbounded\" NUMBER,\"binary\" FLOAT(53),\"bytes\" VARCHAR2(12 BYTE),\"chars\" VARCHAR2(12 CHAR),"
                +"\"national\" NCHAR(7),\"raw\" RAW(13),\"stamp\" TIMESTAMP(0),\"nano\" TIMESTAMP(9))");
        sql("COMMENT ON TABLE "+table("source_rows")+" IS '表''\\说明'");
        sql("COMMENT ON COLUMN "+table("source_rows")+".\"negative\" IS '负''标度'");
        sql("INSERT INTO "+table("source_rows")+" (\"id\",\"negative\",\"fraction\") VALUES(1,12300,0.00123)");
        assertSuccess(run("source_rows","created_rows",false,target->target.setOnlyCreate(true)));
        assertEquals(catalog("source_rows"),catalog("created_rows"));
        assertEquals(primaryKey("source_rows"),primaryKey("created_rows"));
        assertEquals(scalar("SELECT comments FROM sys.all_tab_comments WHERE owner='"+schema+"' AND table_name='source_rows'"),
                scalar("SELECT comments FROM sys.all_tab_comments WHERE owner='"+schema+"' AND table_name='created_rows'"));
        assertEquals("0",scalar("SELECT COUNT(*) FROM "+table("created_rows")));
        assertSuccess(run("source_rows","created_rows",false));
        assertEquals("12300",scalar("SELECT \"negative\" FROM "+table("created_rows")));
        assertEquals("0.00123",scalar("SELECT \"fraction\" FROM "+table("created_rows")));
    }
    @Test void sourceTriggerCannotDisappearDuringAutoCreate()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");
        sql("CREATE TRIGGER "+table("source_trigger")+" BEFORE INSERT ON "+table("source_rows")+" FOR EACH ROW BEGIN :NEW.\"id\" := 2; END;");
        assertFalse(run("source_rows","created_rows",false,target->target.setOnlyCreate(true)).getIfAllSuccess().get());
        assertEquals("0",scalar("SELECT COUNT(*) FROM sys.all_objects WHERE owner='"+schema+"' AND object_name='created_rows' AND object_type='TABLE'"));
    }
    @Test void unsupportedDependentDefaultFailsBeforeExistingTargetTruncate()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) DEFAULT 42 PRIMARY KEY)");
        create("target_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");sql("INSERT INTO "+table("target_rows")+" VALUES(9)");
        var result=run("source_rows","target_rows",false,target->target.setTargetTruncateData(true));
        assertFalse(result.getIfAllSuccess().get());assertEquals("9",scalar("SELECT \"id\" FROM "+table("target_rows")));
    }
    @Test void replacementRequiresExclusiveDdlOwnershipAndPreservesRecoveryIdentity()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");create("target_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");
        sql("INSERT INTO "+table("source_rows")+" VALUES(1)");sql("INSERT INTO "+table("target_rows")+" VALUES(9)");
        var rejected=run("source_rows","target_rows",false,target->target.setTargetDrop(true));
        assertFalse(rejected.getIfAllSuccess().get());assertEquals("9",scalar("SELECT \"id\" FROM "+table("target_rows")));
        String prior=objectId("target_rows");
        var accepted=run("source_rows","target_rows",false,target->{target.setTargetDrop(true);target.setExclusiveTargetDdlOwnership(true);});
        assertSuccess(accepted);assertNotEquals(prior,objectId("target_rows"));assertEquals("1",scalar("SELECT \"id\" FROM "+table("target_rows")));
        var result=accepted.getTableResultList().getFirst();assertNotNull(result.getNativePlanDigest());
        assertTrue(result.getNativeRecoveryResources().getFirst().expectedPhysicalIdentity().endsWith(":"+objectId("target_rows")));
    }
    @Test void targetReplacementBetweenPlanAndExecuteIsRejectedWithoutDdl()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");create("target_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");
        var ds=dataSource();var evidence=OracleNativeTables.describe(ds,schema,"source_rows",20);
        var plan=OracleNativeTables.plan(ds,evidence,schema,"target_rows",Map.of("id","id"),true,20);
        sql("DROP TABLE "+table("target_rows"));create("target_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");
        sql("INSERT INTO "+table("target_rows")+" VALUES(9)");String identity=objectId("target_rows");
        assertThrows(IllegalStateException.class,plan::execute);
        assertEquals(identity,objectId("target_rows"));assertEquals("9",scalar("SELECT \"id\" FROM "+table("target_rows")));
    }
    @Test void mismatchedSourceCharacterDomainCannotSilentlyNarrowTarget()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) PRIMARY KEY,\"text\" VARCHAR2(12 CHAR))");
        var evidence=OracleNativeTables.describe(dataSource(),schema,"source_rows",20);
        var document=cn.hutool.json.JSONUtil.parseObj(evidence.document()).set("databaseCharset","US7ASCII");
        var foreign=new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(ProductTypeEnum.ORACLE,evidence.format(),document.toString(),evidence.fields());
        assertThrows(IllegalStateException.class,()->OracleNativeTables.plan(dataSource(),foreign,schema,"created_rows",Map.of("id","id","text","text"),false,20));
        assertEquals("0",scalar("SELECT COUNT(*) FROM sys.all_objects WHERE owner='"+schema+"' AND object_name='created_rows' AND object_type='TABLE'"));
    }
    @Test void createCommittedButAcknowledgementLostRemainsUnknownAndCannotReplay()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) PRIMARY KEY)");
        var evidence=OracleNativeTables.describe(dataSource(),schema,"source_rows",20);
        var fault=new DriverManagerDataSource(url(),schema,userPassword) {
            @Override public Connection getConnection()throws SQLException {
                Connection actual=super.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try {
                        Object result=method.invoke(actual,args);
                        if(method.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                            try {Object value=m.invoke(result,a);if(m.getName().equals("execute")&&a[0] instanceof String sql&&sql.startsWith("CREATE TABLE"))throw new SQLException("Injected lost DDL acknowledgement","08006");return value;}
                            catch(InvocationTargetException e){throw e.getCause();}
                        });
                        return result;
                    }catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        var plan=OracleNativeTables.plan(fault,evidence,schema,"created_rows",Map.of("id","id"),false,20);
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertNotNull(objectId("created_rows"));
        assertNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());
        assertThrows(IllegalStateException.class,plan::execute);
    }
    @Test void keysOnlyDifferenceSupportsInsertAndDelete()throws Exception {
        create("source_rows","(\"id\" NUMBER(19,0) NOT NULL PRIMARY KEY)");
        create("target_rows","(\"id\" NUMBER(19,0) NOT NULL PRIMARY KEY)");
        for(int value:List.of(1,3))sql("INSERT INTO "+table("source_rows")+" VALUES("+value+")");
        for(int value:List.of(1,2))sql("INSERT INTO "+table("target_rows")+" VALUES("+value+")");
        assertSuccess(run("source_rows","target_rows",true));
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT \"id\" FROM "+table("target_rows")+" ORDER BY \"id\"")) {
            assertTrue(rows.next());assertEquals(1,rows.getLong(1));assertTrue(rows.next());assertEquals(3,rows.getLong(1));assertFalse(rows.next());
        }
    }
    private String columns(String note) {
        return "(\"part\" NUMBER(3,0) NOT NULL,\"id\" NUMBER(19,0) NOT NULL,\"amount\" NUMBER(38,18),"+q(note)
                +" NVARCHAR2(256),\"payload\" BLOB,\"event_time\" TIMESTAMP(9),\"document\" CLOB,PRIMARY KEY(\"part\",\"id\"))";
    }
    private void seed(String table,String note)throws Exception {
        try(var statement=control.prepareStatement("INSERT INTO "+table(table)+" VALUES(?,?,?,?,?,?,?)")) {
            for(int i=0;i<503;i++) {
                statement.setInt(1,i%2);statement.setLong(2,9007199254740993L+i);
                statement.setBigDecimal(3,i%3==0?null:new BigDecimal("12345678901234567890.123456789012345678"));
                statement.setNString(4,i%3==0?null:"中文'\\\n😀"+i);
                if(i%3==0)statement.setNull(5,Types.BLOB);else statement.setBytes(5,new byte[]{0,(byte)i,(byte)255});
                statement.setTimestamp(6,Timestamp.valueOf("2026-10-03 12:34:56.123456789"));
                statement.setString(7,i%3==0?null:"长文本'\\\n".repeat(1000)+i);statement.addBatch();
            }
            statement.executeBatch();
        }
    }
    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff)throws Exception {
        return run(sourceTable,targetTable,diff,target->{});
    }
    private DbSwitchResult run(String sourceTable,String targetTable,boolean diff,
            java.util.function.Consumer<cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties> configure)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.ORACLE);source.setUrl(url());source.setUsername(schema);source.setPassword(userPassword);
        source.setSourceSchema(schema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(sourceTable)+"$",targetTable)));
        request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.ORACLE);target.setUrl(url());target.setUsername(schema);target.setPassword(userPassword);
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setChangeDataSync(diff);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        configure.accept(target);
        try(var runtime=DbSwitchRuntime.standalone()) {return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private DriverManagerDataSource dataSource(){return new DriverManagerDataSource(url(),schema,userPassword);}
    private String scalar(String sql)throws Exception {try(var s=control.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());String value=r.getString(1);assertFalse(r.next());return value;}}
    private String objectId(String table)throws Exception{return scalar("SELECT object_id FROM sys.all_objects WHERE owner='"+schema+"' AND object_name='"+table+"' AND object_type='TABLE'");}
    private List<List<Object>> catalog(String table)throws Exception {
        var values=new ArrayList<List<Object>>();
        try(var p=control.prepareStatement("SELECT c.column_name,c.data_type,c.data_precision,c.data_scale,c.data_length,c.char_length,c.char_used,c.nullable,m.comments FROM sys.all_tab_columns c LEFT JOIN sys.all_col_comments m ON m.owner=c.owner AND m.table_name=c.table_name AND m.column_name=c.column_name WHERE c.owner=? AND c.table_name=? ORDER BY c.column_id")) {
            p.setString(1,schema);p.setString(2,table);try(var r=p.executeQuery()){while(r.next()){var row=new ArrayList<>();for(int i=1;i<=9;i++)row.add(r.getObject(i));values.add(row);}}
        }return values;
    }
    private List<String> primaryKey(String table)throws Exception {
        var names=new ArrayList<String>();try(var p=control.prepareStatement("SELECT cc.column_name FROM sys.all_constraints c JOIN sys.all_cons_columns cc ON c.owner=cc.owner AND c.constraint_name=cc.constraint_name WHERE c.owner=? AND c.table_name=? AND c.constraint_type='P' ORDER BY cc.position")){
            p.setString(1,schema);p.setString(2,table);try(var r=p.executeQuery()){while(r.next())names.add(r.getString(1));}
        }return names;
    }
    private List<List<Object>> rows(String table,String note)throws Exception {
        var result=new ArrayList<List<Object>>();
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT \"part\",\"id\",\"amount\","+q(note)+",\"payload\",\"event_time\",\"document\" FROM "+table(table)+" ORDER BY \"part\",\"id\"")) {
            while(rows.next())result.add(Arrays.asList(rows.getInt(1),rows.getLong(2),rows.getBigDecimal(3),rows.getNString(4),
                    rows.getBytes(5)==null?null:HexFormat.of().formatHex(rows.getBytes(5)),rows.getTimestamp(6),rows.getString(7)));
        }
        return result;
    }
    private void assertRowsEqual(String source,String target,String note)throws Exception {
        var expected=rows(source,note);var actual=rows(target,note);assertEquals(expected.size(),actual.size(),"row count");
        for(int row=0;row<expected.size();row++)for(int column=0;column<expected.get(row).size();column++)
            assertTrue(Objects.equals(expected.get(row).get(column),actual.get(row).get(column)),"Exact value mismatch at row="+row+", column="+column);
    }
    private void create(String table,String columns)throws Exception {sql("CREATE TABLE "+table(table)+columns);}
    private void sql(String text)throws Exception {try(var statement=control.createStatement()){statement.setQueryTimeout(20);statement.execute(text);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return '"'+name.replace("\"","\"\"")+'"';}
    private static String url(){return System.getProperty("datahub.test.oracle.url");}
    private static void assertSuccess(DbSwitchResult result) {
        assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
    }
}
