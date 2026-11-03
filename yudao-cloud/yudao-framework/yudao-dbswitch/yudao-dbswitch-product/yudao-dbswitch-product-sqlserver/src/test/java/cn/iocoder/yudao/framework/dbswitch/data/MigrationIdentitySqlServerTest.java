package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityReplacement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.mssql.SqlServerWriterImpl;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Task-owned random objects only: explicit IDs, native counter state and public migration guards. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
class MigrationIdentitySqlServerTest {
    private Connection control;
    private String schema;
    private final Set<String> tables=new LinkedHashSet<>();
    @BeforeEach void setup()throws Exception {
        control=DriverManager.getConnection(url(),"sa",password());schema="identity_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {
        if(control!=null)try(var owned=control){for(String name:tables)sql("DROP TABLE IF EXISTS "+table(name));sql("DROP SCHEMA "+q(schema));}
    }
    @Test void publicFullPreservesNumeric38AndDeletedSourceHighWater()throws Exception {
        String seed="99999999999999999999999999999999999900";
        create("source]; ids","numeric(38,0)",seed,"7",false);tables.add("target]; ids");
        sql("INSERT INTO "+table("source]; ids")+"(note) VALUES(N'first'),(N'second'),(N'deleted')");
        sql("DELETE FROM "+table("source]; ids")+" WHERE note=N'deleted'");
        BigInteger expected=next("source]; ids");
        assertSuccess(run(request("source]; ids","target]; ids")));
        assertEquals(rows("source]; ids"),rows("target]; ids"));assertEquals(expected,generated("target]; ids"));
    }
    @ParameterizedTest @ValueSource(strings={"APPEND","RECREATE","TRUNCATE"})
    void fullNeverRewindsExistingTargetIdentity(String mode)throws Exception {
        create("source_rows","bigint","5","3",false);create("target_rows","bigint","5","3",false);
        sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'one'),(N'two')");
        sql("INSERT INTO "+table("target_rows")+"(note) VALUES(N'old')");reseed("target_rows","1001");
        if(mode.equals("APPEND"))sql("DELETE FROM "+table("target_rows"));
        var request=request("source_rows","target_rows");request.getTarget().setTargetDrop(mode.equals("RECREATE"));
        request.getTarget().setTargetTruncateData(mode.equals("TRUNCATE"));
        assertSuccess(run(request));assertEquals(rows("source_rows"),rows("target_rows"));assertEquals(new BigInteger("1004"),generated("target_rows"));
    }
    @Test void negativeIdentityPreservesLowestSourcePosition()throws Exception {
        create("source_rows","bigint","-5","-3",false);tables.add("target_rows");
        sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'first'),(N'last')");sql("DELETE FROM "+table("source_rows")+" WHERE note=N'last'");
        assertSuccess(run(request("source_rows","target_rows")));assertEquals(rows("source_rows"),rows("target_rows"));
        assertEquals(new BigInteger("-11"),generated("target_rows"));
    }
    @ParameterizedTest @ValueSource(strings={"FRESH","RESEEDED","DELETED","TRUNCATED","ROLLED_BACK"})
    void emptySourcePreservesDistinctFirstAllocationStates(String state)throws Exception {
        create("source_rows","int","5","3",false);tables.add("target_rows");
        if(state.equals("DELETED")||state.equals("TRUNCATED")) {
            sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'removed')");
            sql((state.equals("DELETED")?"DELETE FROM ":"TRUNCATE TABLE ")+table("source_rows"));
        }
        if(state.equals("ROLLED_BACK")) {
            control.setAutoCommit(false);sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'rolled back')");control.rollback();control.setAutoCommit(true);
        }
        if(!state.equals("FRESH"))reseed("source_rows","41");
        BigInteger expected=next("source_rows");assertSuccess(run(request("source_rows","target_rows")));
        assertTrue(rows("target_rows").isEmpty());assertEquals(expected,generated("target_rows"));
    }
    @Test void replacementSamplesTargetAgainUnderTheDdlTransactionLock()throws Exception {
        create("source_rows","int","5","3",false);create("target_rows","int","5","3",false);
        sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'one')");sql("INSERT INTO "+table("target_rows")+"(note) VALUES(N'old')");
        reseed("target_rows","41");
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(url(),"sa",password());
        var writer=new SqlServerWriterImpl(ds);writer.configureIdentityTransfer(options());writer.preflightIdentityTransfer(schema,"target_rows",true);
        reseed("target_rows","1001");
        assertTrue(writer.prepareIdentityReplacement(new IdentityReplacement(schema,"target_rows",IdentityReplacement.Mode.TRUNCATE,List.of())));
        writer.prepareWrite(schema,"target_rows",List.of("id","note"));
        writer.write(List.of("id","note"),List.<Object[]>of(new Object[]{5,"one"}));writer.completeIdentityTransfer();
        assertEquals(new BigInteger("1004"),generated("target_rows"));
    }
    @Test void missingSourceQuiescenceRejectsBeforeDrop()throws Exception {
        create("source_rows","int","5","3",false);create("target_rows","int","5","3",false);
        sql("INSERT INTO "+table("target_rows")+"(note) VALUES(N'old')");var before=rows("target_rows");
        var request=request("source_rows","target_rows");request.getSource().getFirst().setIdentitySourceQuiesced(false);request.getTarget().setTargetDrop(true);
        assertFalse(run(request).getIfAllSuccess().get());assertEquals(before,rows("target_rows"));
    }
    @Test void exhaustedSourceRejectsBeforeRequestedTargetReplacement()throws Exception {
        create("source_rows","tinyint","255","1",false);create("target_rows","tinyint","255","1",false);
        sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'exhausted')");
        var before=NativeSchemaReader.readSqlServerIdentityTable(control,schema,"target_rows",20);
        var request=request("source_rows","target_rows");request.getTarget().setTargetDrop(true);
        assertFalse(run(request).getIfAllSuccess().get());
        var after=NativeSchemaReader.readSqlServerIdentityTable(control,schema,"target_rows",20);
        assertTrue(before.samePhysicalTable(after));assertTrue(rows("target_rows").isEmpty());
    }
    @Test void samePhysicalSourceAndTargetRejectsBeforeAnyReplacement()throws Exception {
        create("source_rows","int","5","3",false);sql("INSERT INTO "+table("source_rows")+"(note) VALUES(N'keep')");
        var before=rows("source_rows");var position=next("source_rows");var request=request("source_rows","source_rows");request.getTarget().setTargetDrop(true);
        assertFalse(run(request).getIfAllSuccess().get());assertEquals(before,rows("source_rows"));assertEquals(position,next("source_rows"));
    }
    @Test void structureOnlyPreservesDefinitionWithoutClaimingCounterTransfer()throws Exception {
        create("source_rows","int","5","3",true);reseed("source_rows","1001");tables.add("target_rows");
        var request=request("source_rows","target_rows");request.getTarget().setPreserveIdentityValues(false);request.getTarget().setExclusiveTargetOwnership(false);
        request.getTarget().setOnlyCreate(true);assertSuccess(run(request));
        var identity=NativeSchemaReader.readSqlServerIdentityTable(control,schema,"target_rows",20).columns().get("id");
        assertTrue(identity.notForReplication());assertEquals(new BigInteger("5"),generated("target_rows"));
    }
    @Test void sourceQuiescenceIsFrozenAndVisibleInJson()throws Exception {
        var request=request("source_rows","target_rows");var frozen=request.snapshot();request.getSource().getFirst().setIdentitySourceQuiesced(false);
        assertTrue(frozen.getSource().getFirst().getIdentitySourceQuiesced());
        var mapper=new tools.jackson.databind.ObjectMapper();String json=mapper.writeValueAsString(frozen.getSource().getFirst());
        assertTrue(json.contains("\"identitySourceQuiesced\":true"));
        assertTrue(mapper.readValue(json,SourceDataSourceProperties.class).getIdentitySourceQuiesced());
        assertFalse(new SourceDataSourceProperties().getIdentitySourceQuiesced());
    }
    private IdentityTransferOptions options()throws Exception {
        return new IdentityTransferOptions(true,true,Map.of(),NativeSchemaReader.readSqlServerIdentityTable(control,schema,"source_rows",20).columns());
    }
    private MigrationRequest request(String from,String to) {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.SQLSERVER);
        source.setUrl(url());source.setUsername("sa");source.setPassword(password());source.setSourceSchema(schema);source.setSourceIncludes(from);
        source.setPoolSize(1);source.setFetchSize(500);source.setIdentitySourceQuiesced(true);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(from)+"$",to)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.SQLSERVER);target.setUrl(url());target.setUsername("sa");target.setPassword(password());
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setPreserveIdentityValues(true);target.setExclusiveTargetOwnership(true);
        return request;
    }
    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult run(MigrationRequest request)throws Exception {
        try(var runtime=DbSwitchRuntime.standalone()){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private void create(String name,String type,String seed,String step,boolean nfr)throws Exception {
        tables.add(name);sql("CREATE TABLE "+table(name)+"(id "+type+" IDENTITY("+seed+","+step+")"+(nfr?" NOT FOR REPLICATION":"")+" NOT NULL PRIMARY KEY,note NVARCHAR(30))");
    }
    private BigInteger next(String name)throws Exception{return NativeSchemaReader.readSqlServerIdentityTable(control,schema,name,20).columns().get("id").nextObservedValue();}
    private BigInteger generated(String name)throws Exception {
        try(var s=control.createStatement();var r=s.executeQuery("INSERT INTO "+table(name)+"(note) OUTPUT INSERTED.id VALUES(N'generated')")){assertTrue(r.next());return r.getBigDecimal(1).toBigIntegerExact();}
    }
    private List<List<String>> rows(String name)throws Exception {
        List<List<String>> result=new ArrayList<>();try(var s=control.createStatement();var r=s.executeQuery("SELECT id,note FROM "+table(name)+" ORDER BY id")) {
            while(r.next())result.add(Arrays.asList(r.getString(1),r.getString(2)));
        }return result;
    }
    private void reseed(String name,String value)throws Exception {sql("DBCC CHECKIDENT ('"+table(name).replace("'","''")+"', RESEED, "+value+") WITH NO_INFOMSGS");}
    private void sql(String text)throws Exception{try(var s=control.createStatement()){s.setQueryTimeout(20);s.execute(text);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return '['+name.replace("]","]]")+']';}
    private static String url(){return System.getProperty("datahub.test.sqlserver.url");}
    private static String password(){return System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD");}
    private static void assertSuccess(cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult result) {
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
    }
}
