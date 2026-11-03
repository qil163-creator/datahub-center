package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generated/custom DDL must establish the COPY subset before replacing a real Greenplum target. */
@EnabledIfSystemProperty(named="datahub.test.greenplum.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
@EnabledIfEnvironmentVariable(named="DATAHUB_GREENPLUM_TEST_PASSWORD",matches=".+")
@Timeout(180)
class GreenplumCopyGeneratedNativeTest {
    private final String schema="gpcopy_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
    private Connection pg,gp;
    @BeforeEach void setup()throws Exception {
        pg=DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user"),System.getProperty("datahub.test.postgres.password",""));
        gp=DriverManager.getConnection(System.getProperty("datahub.test.greenplum.url"),
                System.getProperty("datahub.test.greenplum.user"),System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"));
        sql(pg,"CREATE SCHEMA "+schema);sql(gp,"CREATE SCHEMA "+schema);
    }
    @AfterEach void cleanup()throws Exception {
        try {if(pg!=null)try{sql(pg,"DROP SCHEMA IF EXISTS "+schema+" CASCADE");}finally{pg.close();}}
        finally{if(gp!=null)try{sql(gp,"DROP SCHEMA IF EXISTS "+schema+" CASCADE");}finally{gp.close();}}
    }
    @ParameterizedTest @ValueSource(strings={"uuid","jsonb","timestamp(6) with time zone"})
    void generatedUnsupportedCopyTypeDoesNotDropSentinel(String type)throws Exception {
        sql(pg,"CREATE TABLE "+schema+".source(id bigint PRIMARY KEY,payload "+type+")");
        sentinel();
        assertFalse(run(null,List.of("id")).getIfAllSuccess().get());
        assertSentinel();
    }
    @ParameterizedTest @ValueSource(strings={"uuid","jsonb","integer[]","timestamp(6) with time zone",
            "numeric(5,6)","numeric(5,-1)","varchar(0)","timestamp(7)",
            "identity","wrong_target","extra_statement","random_distribution"})
    void customUnsupportedCopyPlanDoesNotDropSentinel(String mode)throws Exception {
        sql(pg,"CREATE TABLE "+schema+".source(id bigint PRIMARY KEY,payload bigint)");
        sentinel();
        String destination=mode.equals("wrong_target")?"another":"copied";
        String type=switch(mode){case "identity"->"bigint GENERATED ALWAYS AS IDENTITY";
            case "wrong_target","extra_statement","random_distribution"->"bigint";default->mode;};
        String ddl="CREATE TABLE "+schema+'.'+destination+"(id bigint PRIMARY KEY,payload "+type+") "
                +(mode.equals("random_distribution")?"DISTRIBUTED RANDOMLY":"DISTRIBUTED BY(id)");
        if(mode.equals("extra_statement"))ddl+="; DROP TABLE "+schema+".copied";
        assertFalse(run(ddl,List.of()).getIfAllSuccess().get());
        assertSentinel();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void generatedAndCustomScalarCopyReplaceTargetWithRequestedDistribution(boolean custom)throws Exception {
        sql(pg,"CREATE TABLE "+schema+".source(id bigint PRIMARY KEY,payload numeric(24,6),note text,raw bytea,flag boolean)");
        sql(pg,"INSERT INTO "+schema+".source VALUES(1,123456789012345678.123456,'中文 '' \\ tab',decode('00ff','hex'),true),"
                +"(2,NULL,NULL,NULL,NULL)");
        sentinel();
        String ddl=custom?"CREATE TABLE "+schema+".copied(id bigint PRIMARY KEY,payload numeric(24,6) DEFAULT 1,note varchar,raw bytea,flag boolean) DISTRIBUTED BY(id)":null;
        var result=run(ddl,custom?List.of():List.of("id"));
        assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        String query="SELECT id,payload,note,encode(raw,'hex'),flag FROM "+schema;
        assertEquals(rows(pg,query+".source ORDER BY id"),rows(gp,query+".copied ORDER BY id"));
        assertEquals(List.of(List.of("id")),rows(gp,"SELECT a.attname FROM gp_distribution_policy p JOIN pg_class t ON t.oid=p.localoid"
                +" JOIN pg_namespace n ON n.oid=t.relnamespace CROSS JOIN LATERAL unnest(p.distkey) k(attnum)"
                +" JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum WHERE n.nspname='"+schema+"' AND t.relname='copied'"));
    }
    private DbSwitchResult run(String custom,List<String> distribution)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);source.setUrl(System.getProperty("datahub.test.postgres.url"));
        source.setUsername(System.getProperty("datahub.test.postgres.user"));source.setPassword(System.getProperty("datahub.test.postgres.password",""));
        source.setSourceSchema(schema);source.setSourceIncludes("source");source.setPoolSize(1);source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$","copied")));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.GREENPLUM);
        target.setUrl(System.getProperty("datahub.test.greenplum.url"));target.setUsername(System.getProperty("datahub.test.greenplum.user"));
        target.setPassword(System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD"));target.setPoolSize(1);target.setTargetSchema(schema);
        target.setTargetDrop(true);target.setWriterEngineInsert(false);target.setDistributedKeys(distribution);
        if(custom!=null)target.setCustomDdlMap(Map.of("copied",custom));
        try(var runtime=DbSwitchRuntime.standalone()){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private void sentinel()throws Exception {
        sql(gp,"CREATE TABLE "+schema+".copied(id bigint PRIMARY KEY,payload text) DISTRIBUTED BY(id)");
        sql(gp,"INSERT INTO "+schema+".copied VALUES(42,'prior COPY target sentinel')");
    }
    private void assertSentinel()throws Exception {
        assertEquals(List.of(List.of("42","prior COPY target sentinel")),rows(gp,"SELECT * FROM "+schema+".copied"));
    }
    private static void sql(Connection connection,String sql)throws Exception {
        try(var statement=connection.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}
    }
    private static List<List<String>> rows(Connection connection,String sql)throws Exception {
        var result=new ArrayList<List<String>>();try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) {
            int count=rows.getMetaData().getColumnCount();while(rows.next()){var row=new ArrayList<String>();for(int i=1;i<=count;i++)row.add(rows.getString(i));result.add(row);}
        }return result;
    }
}
