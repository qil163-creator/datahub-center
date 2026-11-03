package cn.iocoder.yudao.framework.dbswitch.data;

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
class MigrationCyclicStructurePostgreSqlTest {
    private final String source="cyclic_src_"+UUID.randomUUID().toString().replace("-","");
    private final String target="cyclic_dst_"+UUID.randomUUID().toString().replace("-","");
    @BeforeEach void setup()throws Exception{sql("CREATE SCHEMA "+source,"CREATE SCHEMA "+target);}
    @AfterEach void cleanup()throws Exception{sql("DROP SCHEMA IF EXISTS "+source+" CASCADE","DROP SCHEMA IF EXISTS "+target+" CASCADE");}

    @ParameterizedTest @CsvSource({"false,false,false","false,false,true","false,true,false","false,true,true",
            "true,false,false","true,false,true","true,true,false","true,true,true"})
    void structureOnlyPreservesCycleDefinitionAndExistingTargetWrap(boolean identity,boolean descending,boolean replace)throws Exception {
        String sourceSequence=create(source,identity,descending);
        long sourceSample=descending?-5:5;
        sql("ALTER SEQUENCE "+sourceSequence+" RESTART WITH "+sourceSample);
        assertEquals(sourceSample,number("SELECT nextval('"+sourceSequence+"')"));
        if(replace) {
            String targetSequence=create(target,identity,descending);
            long edge=descending?-8:8;
            sql("ALTER SEQUENCE "+targetSequence+" RESTART WITH "+edge);
            assertEquals(edge,number("SELECT nextval('"+targetSequence+"')"));
        }
        var request=request();request.getTarget().setTargetDrop(replace);
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request,ignored->{},null,runtime).run();
            assertTrue(result.getIfAllSuccess().get());assertEquals(0,result.getTotalRowCount().get());
            assertEquals(2,result.getTableResultList().size());
        }
        assertEquals(0,number("SELECT (SELECT count(*) FROM "+target+".owner)+(SELECT count(*) FROM "+target+".peer)"));
        assertEquals(1,number("SELECT count(*) FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+target+"' AND s.seqcycle"));
        long first=replace?(descending?-1:1):(descending?-2:2);
        assertEquals(first,number("INSERT INTO "+target+".peer(note) VALUES('first') RETURNING id"));
        assertEquals(first+(descending?-3:3),number("INSERT INTO "+target+".owner(note) VALUES('second') RETURNING id"));
        assertEquals(sourceSample,number("SELECT last_value FROM "+sourceSequence));
        assertEquals(identity?"a":"",text("SELECT attidentity FROM pg_attribute WHERE attrelid='"+target+".owner'::regclass AND attname='id'"));
    }

    private String create(String schema,boolean identity,boolean descending)throws Exception {
        String options="START WITH "+(descending?"-2 INCREMENT BY -3 MINVALUE -8 MAXVALUE -1":"2 INCREMENT BY 3 MINVALUE 1 MAXVALUE 8")+" CACHE 1 CYCLE";
        String sequence=schema+(identity?".owner_id_seq":".shared");
        if(identity)sql("CREATE TABLE "+schema+".owner(id bigint GENERATED ALWAYS AS IDENTITY ("+options+") PRIMARY KEY,note text)");
        else sql("CREATE SEQUENCE "+sequence+" AS bigint "+options,
                "CREATE TABLE "+schema+".owner(id bigint DEFAULT nextval('"+sequence+"'::regclass) PRIMARY KEY,note text)",
                "ALTER SEQUENCE "+sequence+" OWNED BY "+schema+".owner.id");
        sql("CREATE TABLE "+schema+".peer(id bigint DEFAULT nextval('"+sequence+"'::regclass) PRIMARY KEY,note text)");
        return sequence;
    }

    @ParameterizedTest @CsvSource({"true","false"})
    void cycleDataTransferCannotAccidentallyUseTheStructureOnlyRestartStrategy(boolean identity)throws Exception {
        create(source,identity,false);create(target,identity,false);
        sql("INSERT INTO "+target+".owner OVERRIDING SYSTEM VALUE VALUES(42,'retain')");
        long oid=number("SELECT '"+target+".owner'::regclass::oid");
        var request=request();request.getTarget().setOnlyCreate(false);request.getTarget().setTargetDrop(true);
        var callbacks=new ArrayList<Object>();
        try(var runtime=DbSwitchRuntime.standalone()){assertThrows(IllegalArgumentException.class,()->new MigrationService(request,callbacks::add,null,runtime).run());}
        assertTrue(callbacks.isEmpty());assertEquals(oid,number("SELECT '"+target+".owner'::regclass::oid"));
        assertEquals(42,number("SELECT id FROM "+target+".owner"));assertEquals(0,number("SELECT count(*) FROM "+target+".peer"));
    }
    private MigrationRequest request() {
        var request=new MigrationRequest();var input=new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.POSTGRESQL);input.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        input.setUrl(url());input.setUsername(user());input.setPassword(password());input.setSourceSchema(source);input.setSourceIncludes("owner,peer");input.setPoolSize(2);request.getSource().add(input);
        var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        output.setUrl(url());output.setUsername(user());output.setPassword(password());output.setTargetSchema(target);output.setPoolSize(2);
        output.setPreserveIdentityValues(true);output.setExclusiveTargetOwnership(true);output.setOnlyCreate(true);return request;
    }
    private static String text(String sql)throws Exception{try(var connection=open();var statement=connection.createStatement();var row=statement.executeQuery(sql)){assertTrue(row.next());return row.getString(1);}}
    private static long number(String sql)throws Exception{return Long.parseLong(text(sql));}
    private static void sql(String... values)throws SQLException{try(var connection=open();var statement=connection.createStatement()){for(String value:values)statement.execute(value);}}
    private static Connection open()throws SQLException{return DriverManager.getConnection(url(),user(),password());}
    private static String url(){return System.getProperty("datahub.test.postgres.url");}
    private static String user(){return System.getProperty("datahub.test.postgres.user","dataforge_test");}
    private static String password(){return System.getProperty("datahub.test.postgres.password","");}
}
