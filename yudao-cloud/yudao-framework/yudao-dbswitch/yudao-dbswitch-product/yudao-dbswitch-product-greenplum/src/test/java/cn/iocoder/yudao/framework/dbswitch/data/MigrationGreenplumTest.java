package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.GreenplumDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumNativeTables;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual GP coordinator plus segments; PostgreSQL alone is not a valid substitute. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.greenplum.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres\\?sslmode=disable")
@EnabledIfEnvironmentVariable(named="DATAHUB_GREENPLUM_TEST_PASSWORD",matches=".+")
class MigrationGreenplumTest {
    private Connection control;
    private String schema;
    @BeforeEach void setup()throws Exception {
        control=DriverManager.getConnection(url(),"gpadmin",password());
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT version()")) {
            assertTrue(rows.next());assertTrue(rows.getString(1).contains("Greenplum Database 7."));
        }
        schema="dbswitch_gp_"+UUID.randomUUID().toString().replace("-","");sql("CREATE SCHEMA "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {if(control!=null)try(var owned=control){if(schema!=null)sql("DROP SCHEMA IF EXISTS "+q(schema)+" CASCADE");}}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicFullCreateAndDifferencePreserveNativePrecisionDistributionAndQuotedIdentifiers(boolean insert)throws Exception {
        String source="source\" rows",target="target\" rows";
        sql("CREATE TABLE "+table(source)+"(part SMALLINT NOT NULL,id BIGINT NOT NULL,amount NUMERIC(38,12) DEFAULT 1.2500,"
                +"\"note\" TEXT DEFAULT 'default;value'::text,payload BYTEA,event_time TIMESTAMP(6),PRIMARY KEY(part,id)) DISTRIBUTED BY (part,id)");
        sql("COMMENT ON TABLE "+table(source)+" IS "+GreenplumNativeTables.literal("source's \\table"));
        sql("COMMENT ON COLUMN "+table(source)+".note IS "+GreenplumNativeTables.literal("column's \\value"));
        seed(source);
        var full=run(source,target,false,insert,false);success(full);
        assertEquals(503,full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertEquals(rows(source),rows(target));
        assertEquals(structure(source),structure(target));
        sql("DELETE FROM "+table(source)+" WHERE part=0 AND id=9007199254740993");
        sql("UPDATE "+table(source)+" SET amount=-0.000000000001,note='changed' WHERE part=1 AND id=9007199254740994");
        sql("INSERT INTO "+table(source)+" VALUES(1,9223372036854775807,99999999999999999999999999.999999999999,NULL,'\\x00ff',NULL)");
        success(run(source,target,true,insert,false));assertEquals(rows(source),rows(target));
        success(run(source,target,true,insert,false));assertEquals(rows(source),rows(target));
    }

    @ParameterizedTest @ValueSource(strings={"heap_random","heap_replicated","ao_row","ao_column"})
    void fullRecreatesStorageCompressionAndDistribution(String mode)throws Exception {
        String storage=mode.startsWith("heap")?"heap":mode;
        String options=storage.equals("heap")?"":" WITH (compresstype=zlib,compresslevel=2)";
        String distribution=mode.equals("heap_replicated")?"DISTRIBUTED REPLICATED":"DISTRIBUTED RANDOMLY";
        sql("CREATE TABLE "+table("source_rows")+"(id BIGINT,note TEXT DEFAULT 'x') USING "+storage+options+' '+distribution);
        sql("INSERT INTO "+table("source_rows")+" VALUES(9007199254740993,'中文'),(2,NULL)");
        success(run("source_rows","target_rows",false,true,false));
        assertEquals(structure("source_rows"),structure("target_rows"));
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT id,note FROM "+table("target_rows")+" ORDER BY id")) {
            assertTrue(rows.next());assertEquals(2,rows.getLong(1));assertNull(rows.getString(2));
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));assertEquals("中文",rows.getString(2));assertFalse(rows.next());
        }
    }

    @Test void invalidNativeSourceCannotDropExistingTarget()throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(id BIGSERIAL PRIMARY KEY,note TEXT) DISTRIBUTED BY(id)");
        sql("CREATE TABLE "+table("target_rows")+"(id BIGINT PRIMARY KEY,note TEXT) DISTRIBUTED BY(id)");
        sql("INSERT INTO "+table("target_rows")+" VALUES(7,'preserve')");
        var result=run("source_rows","target_rows",false,true,true);
        assertFalse(result.getIfAllSuccess().get());assertEquals("preserve",single("SELECT note FROM "+table("target_rows")));
    }

    @ParameterizedTest @ValueSource(strings={"heap_random","heap_replicated","ao_row","ao_column","array","timestamptz","timetz"})
    void copyUnsupportedNativeSourcePreservesTargetBeforeReplacement(String mode)throws Exception {
        String type=switch(mode){case "array"->"bigint[]";case "timestamptz"->"timestamp with time zone";
            case "timetz"->"time with time zone";default->"text";};
        String storage=mode.startsWith("ao_")?mode:"heap";
        String distribution=switch(mode){case "heap_random"->"DISTRIBUTED RANDOMLY";
            case "heap_replicated"->"DISTRIBUTED REPLICATED";default->"DISTRIBUTED BY(id)";};
        sql("CREATE TABLE "+table("source_rows")+"(id BIGINT,note "+type+") USING "+storage+' '+distribution);
        sql("INSERT INTO "+table("source_rows")+"(id) VALUES(1)");
        simple("target_rows");sql("INSERT INTO "+table("target_rows")+" VALUES(7,'COPY preflight sentinel')");
        String before=structure("target_rows");
        var result=run("source_rows","target_rows",false,false,true);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals("COPY preflight sentinel",single("SELECT note FROM "+table("target_rows")));
        assertEquals(before,structure("target_rows"));
    }

    @Test void copyFullTruncatesPreparedTargetAndWritesNewGeneration()throws Exception {
        simple("source_rows");simple("target_rows");
        sql("INSERT INTO "+table("source_rows")+" VALUES(1,'new COPY rows')");
        sql("INSERT INTO "+table("target_rows")+" VALUES(7,'old COPY rows')");
        success(run("source_rows","target_rows",false,false,false,false,true));
        assertEquals("new COPY rows",single("SELECT note FROM "+table("target_rows")));
    }

    @Test void replacePreservesCheckDefaultAndDoesNotKeepOldRows()throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(id BIGINT PRIMARY KEY,note TEXT DEFAULT 'literal;value',amount NUMERIC(20,5) CHECK(amount>=0)) DISTRIBUTED BY(id)");
        sql("INSERT INTO "+table("source_rows")+"(id,amount) VALUES(9007199254740993,123456789012345.12345)");
        simple("target_rows");sql("INSERT INTO "+table("target_rows")+" VALUES(7,'old')");
        success(run("source_rows","target_rows",false,true,true));
        assertEquals(structure("source_rows"),structure("target_rows"));
        assertEquals("literal;value",single("SELECT note FROM "+table("target_rows")));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+table("target_rows")+"(id,amount) VALUES(2,-1)"));
    }

    @Test void dependentViewPreventsReplaceAndTransactionPreservesTarget()throws Exception {
        simple("source_rows");simple("target_rows");sql("INSERT INTO "+table("target_rows")+" VALUES(7,'preserve')");
        sql("CREATE VIEW "+table("dependent_view")+" AS SELECT * FROM "+table("target_rows"));
        var result=run("source_rows","target_rows",false,true,true);
        assertFalse(result.getIfAllSuccess().get());assertEquals("preserve",single("SELECT note FROM "+table("target_rows")));
        assertEquals("preserve",single("SELECT note FROM "+table("dependent_view")));
    }

    @Test void unmodeledSecondaryIndexRejectsBeforeTargetReplacement()throws Exception {
        simple("source_rows");simple("target_rows");
        sql("CREATE INDEX secondary_note ON "+table("source_rows")+" USING hash(note)");
        sql("INSERT INTO "+table("target_rows")+" VALUES(7,'preserve indexed source')");
        var result=run("source_rows","target_rows",false,true,true);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals("preserve indexed source",single("SELECT note FROM "+table("target_rows")));
    }

    @Test void changedTargetAndForgedDdlAreRejectedBeforeDrop()throws Exception {
        simple("source_rows");simple("target_rows");sql("INSERT INTO "+table("target_rows")+" VALUES(7,'preserve')");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url(),"gpadmin",password());
        var operator=new GreenplumDatabaseOperator(source);
        var definition=operator.describeNativeTable(schema,"source_rows");
        var mapping=Map.of("id","id","note","note");
        var plan=operator.prepareNativeTarget(definition,schema,"target_rows",mapping,true);
        sql("ALTER TABLE "+table("target_rows")+" ADD COLUMN later INTEGER");
        assertThrows(RuntimeException.class,plan::execute);
        assertEquals("preserve",single("SELECT note FROM "+table("target_rows")));
        var json=cn.hutool.json.JSONUtil.parseObj(definition.document());
        json.getJSONObject("definition").getJSONArray("columns").getJSONObject(0).set("type","BIGINT); DROP TABLE danger; --");
        var forged=new NativeTableDefinition(ProductTypeEnum.GREENPLUM,definition.format(),json.toString(),definition.fields());
        assertThrows(IllegalArgumentException.class,()->operator.prepareNativeTarget(forged,schema,"target_rows",mapping,true));
        assertThrows(IllegalArgumentException.class,()->operator.prepareNativeTarget(definition,schema,"source_rows",mapping,true));
    }

    @Test void onlyCreateUsesNativePlanWithoutCopyingRows()throws Exception {
        simple("source_rows");sql("INSERT INTO "+table("source_rows")+" VALUES(1,'source')");
        success(run("source_rows","target_rows",false,true,false,true));
        assertEquals(structure("source_rows"),structure("target_rows"));
        assertEquals("0",single("SELECT count(*) FROM "+table("target_rows")));
    }

    @Test void columnMappingPreservesCompositeDistributionAndKeyOrder()throws Exception {
        sql("CREATE TABLE "+table("source_rows")+"(part SMALLINT,id BIGINT,note TEXT,PRIMARY KEY(part,id)) DISTRIBUTED BY(part,id)");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url(),"gpadmin",password());
        var operator=new GreenplumDatabaseOperator(source);
        var definition=operator.describeNativeTable(schema,"source_rows");
        var plan=operator.prepareNativeTarget(definition,schema,"target_rows",Map.of("part","part\" key","id","identifier","note","text value"),false);
        plan.execute();assertThrows(IllegalStateException.class,plan::execute);
        var parsed=cn.hutool.json.JSONUtil.parseObj(operator.describeNativeTable(schema,"target_rows").document()).getJSONObject("definition");
        assertEquals(List.of("part\" key","identifier"),parsed.getJSONArray("distributionKeys").toList(String.class));
        assertEquals(List.of("part\" key","identifier"),parsed.getJSONArray("constraints").getJSONObject(0).getJSONArray("columns").toList(String.class));
    }

    @Test void truncateAfterPlanningInvalidatesPhysicalGeneration()throws Exception {
        simple("source_rows");simple("target_rows");
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url(),"gpadmin",password());
        var operator=new GreenplumDatabaseOperator(source);
        var plan=operator.prepareNativeTarget(operator.describeNativeTable(schema,"source_rows"),schema,"target_rows",Map.of("id","id","note","note"),true);
        sql("TRUNCATE TABLE "+table("target_rows"));sql("INSERT INTO "+table("target_rows")+" VALUES(7,'after truncate')");
        assertThrows(RuntimeException.class,plan::execute);
        assertEquals("after truncate",single("SELECT note FROM "+table("target_rows")));
    }

    private void simple(String name)throws Exception{sql("CREATE TABLE "+table(name)+"(id BIGINT PRIMARY KEY,note TEXT) DISTRIBUTED BY(id)");}
    private void seed(String name)throws Exception {
        try(var statement=control.prepareStatement("INSERT INTO "+table(name)+" VALUES(?,?,?,?,?,?)")) {
            for(int index=0;index<503;index++) {
                statement.setShort(1,(short)(index%2));statement.setLong(2,9007199254740993L+index);
                statement.setBigDecimal(3,index%3==0?null:new BigDecimal("12345678901234567890123456.123456789012"));
                statement.setString(4,index%3==0?null:"中文'\\;\n😀"+index);statement.setBytes(5,new byte[]{0,(byte)index,(byte)255});
                statement.setTimestamp(6,Timestamp.valueOf(LocalDateTime.of(2026,10,3,12,34,56,123456000)));statement.addBatch();
            }statement.executeBatch();
        }
    }
    private DbSwitchResult run(String from,String to,boolean diff,boolean insert,boolean replace)throws Exception {
        return run(from,to,diff,insert,replace,false);
    }
    private DbSwitchResult run(String from,String to,boolean diff,boolean insert,boolean replace,boolean onlyCreate)throws Exception {
        return run(from,to,diff,insert,replace,onlyCreate,false);
    }
    private DbSwitchResult run(String from,String to,boolean diff,boolean insert,boolean replace,boolean onlyCreate,boolean truncate)throws Exception {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.GREENPLUM);source.setUrl(url());source.setUsername("gpadmin");source.setPassword(password());
        source.setSourceSchema(schema);source.setSourceIncludes(from);source.setPoolSize(1);source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+java.util.regex.Pattern.quote(from)+"$",to)));
        request.getSource().add(source);var target=request.getTarget();
        target.setTargetProductType(ProductTypeEnum.GREENPLUM);target.setUrl(url());target.setUsername("gpadmin");target.setPassword(password());
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(insert);target.setChangeDataSync(diff);target.setTargetDrop(replace);
        target.setOnlyCreate(onlyCreate);
        target.setTargetTruncateData(truncate);
        if(diff)target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()){return new MigrationService(request,ignored->{},null,runtime).run();}
    }
    private List<List<Object>> rows(String name)throws Exception {
        var output=new ArrayList<List<Object>>();try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT part,id,amount,note,payload,event_time FROM "+table(name)+" ORDER BY part,id")) {
            while(rows.next())output.add(Arrays.asList(rows.getShort(1),rows.getLong(2),rows.getBigDecimal(3),rows.getString(4),HexFormat.of().formatHex(rows.getBytes(5)),rows.getTimestamp(6)));
        }return output;
    }
    private String structure(String name) {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url(),"gpadmin",password());
        return cn.hutool.json.JSONUtil.parseObj(new GreenplumDatabaseOperator(source).describeNativeTable(schema,name).document()).getJSONObject("definition").toString();
    }
    private String single(String sql)throws Exception{try(var statement=control.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());String value=rows.getString(1);assertFalse(rows.next());return value;}}
    private static void success(DbSwitchResult result){assertEquals(1,result.getTableResultList().size());assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}
    private void sql(String sql)throws Exception{try(var statement=control.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}}
    private String table(String name){return q(schema)+'.'+q(name);}
    private static String q(String name){return GreenplumNativeTables.quote(name);}
    private static String url(){return System.getProperty("datahub.test.greenplum.url");}
    private static String password(){return System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD");}
}
