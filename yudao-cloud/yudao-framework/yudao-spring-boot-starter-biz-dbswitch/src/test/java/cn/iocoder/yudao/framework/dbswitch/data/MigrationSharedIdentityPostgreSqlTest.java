package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationSharedIdentityPostgreSqlTest {
    private final String source="shared_ident_src_"+UUID.randomUUID().toString().replace("-","");
    private final String target="shared_ident_dst_"+UUID.randomUUID().toString().replace("-","");
    @BeforeEach void setup()throws Exception{sql("CREATE SCHEMA "+source,"CREATE SCHEMA "+target);}
    @AfterEach void cleanup()throws Exception{sql("DROP SCHEMA IF EXISTS "+source+" CASCADE","DROP SCHEMA IF EXISTS "+target+" CASCADE");}

    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void preservesNativeIdentityAndDependentDefaultThroughActualInsertAndCopy(boolean always,boolean insert)throws Exception {
        createSource(always);var request=request(insert);
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request,ignored->{},null,runtime).run();
            assertTrue(result.getIfAllSuccess().get());assertEquals(2,result.getTotalRowCount().get());
            assertEquals(2,result.getTableResultList().stream().mapToLong(row->row.getTransferSummary().acknowledgedRows()).sum());
        }
        assertEquals(5,number("SELECT key_id FROM "+target+".copy_z_owner"));assertEquals(100,number("SELECT key_id FROM "+target+".copy_a_peer"));
        assertEquals(always?"a":"d",text("SELECT a.attidentity FROM pg_attribute a WHERE a.attrelid='"+target+".copy_z_owner'::regclass AND a.attname='key_id'"));
        assertEquals(1,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND c.relkind='S'"));
        assertEquals(101,number("INSERT INTO "+target+".copy_z_owner(note) VALUES('owner') RETURNING key_id"));
        assertEquals(104,number("INSERT INTO "+target+".copy_a_peer(note) VALUES('peer') RETURNING key_id"));
        if(always)assertThrows(SQLException.class,()->sql("INSERT INTO "+target+".copy_z_owner VALUES(800,'forbidden without override')"));
        else {sql("INSERT INTO "+target+".copy_z_owner VALUES(800,'explicit by default')");assertEquals(800,number("SELECT max(key_id) FROM "+target+".copy_z_owner"));}
        assertEquals(5,number("SELECT last_value FROM "+source+".z_owner_id_seq"));
    }

    @ParameterizedTest @CsvSource({"true","false"})
    void structureOnlyKeepsSharedIdentityAtItsDefinitionStart(boolean always)throws Exception {
        createSource(always);sql("ALTER SEQUENCE "+source+".z_owner_id_seq RESTART WITH 800");var request=request(true);request.getTarget().setOnlyCreate(true);
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get());assertEquals(0,result.getTotalRowCount().get());
        }
        assertEquals(0,number("SELECT (SELECT count(*) FROM "+target+".copy_a_peer)+(SELECT count(*) FROM "+target+".copy_z_owner)"));
        assertEquals(5,number("INSERT INTO "+target+".copy_a_peer(note) VALUES('peer') RETURNING key_id"));
        assertEquals(8,number("INSERT INTO "+target+".copy_z_owner(note) VALUES('owner') RETURNING key_id"));
        assertEquals(800,number("SELECT last_value FROM "+source+".z_owner_id_seq"));
    }

    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void existingIdentityPositionNeverMovesBackOnReplaceOrTruncate(boolean always,boolean replace)throws Exception {
        createSource(always);createTarget(always);String sequence=text("SELECT pg_get_serial_sequence('"+target+".copy_z_owner','key_id')");
        sql("ALTER SEQUENCE "+sequence+" RESTART WITH 1001");var request=request(true);request.getTarget().setTargetDrop(replace);request.getTarget().setTargetTruncateData(!replace);
        try(var runtime=DbSwitchRuntime.standalone()){assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());}
        assertEquals(1001,number("INSERT INTO "+target+".copy_a_peer(note) VALUES('new') RETURNING key_id"));
        assertEquals(always?"a":"d",text("SELECT attidentity FROM pg_attribute WHERE attrelid='"+target+".copy_z_owner'::regclass AND attname='key_id'"));
    }

    @Test void lateIdentityOwnerDdlFailureRollsBackEarlierDependentTableAndNeverPublishes()throws Exception {
        createSource(true);sql("CREATE VIEW "+target+".copy_z_owner AS SELECT 9::bigint AS key_id,'retain'::text AS note");
        var callbacks=new ArrayList<Object>();
        try(var runtime=DbSwitchRuntime.standalone()){assertThrows(RuntimeException.class,()->new MigrationService(request(true),callbacks::add,null,runtime).run());}
        assertTrue(callbacks.isEmpty());assertEquals(0,number("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND c.relkind IN ('r','S')"));
        assertEquals(9,number("SELECT key_id FROM "+target+".copy_z_owner"));
    }

    @ParameterizedTest @CsvSource({"true","false"})
    void differentTargetIdentityKindIsRejectedBeforeTruncateOrReplacement(boolean replace)throws Exception {
        createSource(true);createTarget(false);
        sql("INSERT INTO "+target+".copy_z_owner VALUES(41,'retain owner')","INSERT INTO "+target+".copy_a_peer VALUES(42,'retain peer')");
        String sequence=text("SELECT pg_get_serial_sequence('"+target+".copy_z_owner','key_id')");
        long ownerOid=number("SELECT '"+target+".copy_z_owner'::regclass::oid");
        sql("ALTER SEQUENCE "+sequence+" RESTART WITH 1001");
        var request=request(true);request.getTarget().setTargetDrop(replace);request.getTarget().setTargetTruncateData(!replace);
        var callbacks=new ArrayList<Object>();
        try(var runtime=DbSwitchRuntime.standalone()){assertThrows(RuntimeException.class,()->new MigrationService(request,callbacks::add,null,runtime).run());}
        assertTrue(callbacks.isEmpty());assertEquals(ownerOid,number("SELECT '"+target+".copy_z_owner'::regclass::oid"));
        assertEquals(41,number("SELECT key_id FROM "+target+".copy_z_owner"));assertEquals(42,number("SELECT key_id FROM "+target+".copy_a_peer"));
        assertEquals(1001,number("SELECT last_value FROM "+sequence));
    }

    private void createSource(boolean always)throws Exception {
        sql("CREATE TABLE "+source+".z_owner(id bigint GENERATED "+(always?"ALWAYS":"BY DEFAULT")+" AS IDENTITY (START WITH 5 INCREMENT BY 3 MINVALUE 1 MAXVALUE 10000 CACHE 1 NO CYCLE) PRIMARY KEY,note text)",
                "CREATE TABLE "+source+".a_peer(id bigint DEFAULT nextval('"+source+".z_owner_id_seq'::regclass) PRIMARY KEY,note text)",
                "INSERT INTO "+source+".z_owner OVERRIDING SYSTEM VALUE VALUES(5,'owner')","INSERT INTO "+source+".a_peer VALUES(100,'peer')");
    }
    private void createTarget(boolean always)throws Exception {
        sql("CREATE TABLE "+target+".copy_z_owner(key_id bigint GENERATED "+(always?"ALWAYS":"BY DEFAULT")+" AS IDENTITY (START WITH 5 INCREMENT BY 3 MINVALUE 1 MAXVALUE 10000 CACHE 1 NO CYCLE) PRIMARY KEY,note text)",
                "CREATE TABLE "+target+".copy_a_peer(key_id bigint DEFAULT nextval('"+target+".copy_z_owner_key_id_seq'::regclass) PRIMARY KEY,note text)");
    }
    private MigrationRequest request(boolean insert) {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();input.setSourceProductType(ProductTypeEnum.POSTGRESQL);input.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        input.setUrl(url());input.setUsername(user());input.setPassword(password());input.setSourceSchema(source);input.setSourceIncludes("a_peer,z_owner");input.setPoolSize(2);input.setFetchSize(1);
        input.setRegexTableMapper(List.of(new PatternMapper("^(a_peer|z_owner)$","copy_$1")));input.setRegexColumnMapper(List.of(new PatternMapper("^id$","key_id")));request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());output.setUrl(url());output.setUsername(user());output.setPassword(password());
        output.setTargetSchema(target);output.setPoolSize(2);output.setPreserveIdentityValues(true);output.setExclusiveTargetOwnership(true);output.setWriterEngineInsert(insert);return request;
    }
    private static String text(String sql)throws Exception{try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery(sql)){assertTrue(row.next());return row.getString(1);}}
    private static long number(String sql)throws Exception{return Long.parseLong(text(sql));}
    private static void sql(String... values)throws SQLException{try(var connection=open();var statement=connection.createStatement()){for(String value:values)statement.execute(value);}}
    private static Connection open()throws SQLException{return DriverManager.getConnection(url(),user(),password());}
    private static String url(){return System.getProperty("datahub.test.postgres.url");}
    private static String user(){return System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(){return System.getProperty("datahub.test.postgres.password","");}
}
