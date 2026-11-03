package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, disposable Doris 4.0 fixture; no configured application data source is used. */
@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.doris.url", matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DorisTableLayoutsDorisTest {
    private Connection control;
    private String schema;

    @BeforeEach void setup() throws Exception {
        control=DriverManager.getConnection(url(),"root","");
        schema="layout_"+UUID.randomUUID().toString().replace("-","");
        sql(control,"CREATE DATABASE "+schema);
    }
    @AfterEach void cleanup() throws Exception {
        if(control!=null)try(var owned=control){if(schema!=null)sql(control,"DROP DATABASE "+schema+" FORCE");}
    }
    @Test void generatedDdlCreatesTheRequestedPartitionsAndBuckets() throws Exception {
        sql(control,DorisTableLayoutsTest.create(DorisTableLayoutsTest.rangeOptions(),schema,"target_rows"));
        assertLayout("target_rows","configured");
        sql(control,"INSERT INTO "+table("target_rows")+" VALUES(1,'low'),(101,'high')");
        assertEquals(2,count("target_rows"));
        assertThrows(SQLException.class,()->sql(control,"INSERT INTO "+table("target_rows")+" VALUES(-1,'outside')"));
    }
    @Test void listRandomAndAutoOptionsExecuteOnTheServer() throws Exception {
        var partition=new DorisCreateTableOptions.Partition("LIST",List.of("id"),List.of(
                new DorisCreateTableOptions.PartitionEntry("p_ids",List.of(List.of("1"),List.of("2")))));
        var options=new DorisCreateTableOptions(partition,"RANDOM",List.of(),"AUTO",null,1);
        sql(control,DorisTableLayoutsTest.create(options,schema,"target_rows"));
        sql(control,"INSERT INTO "+table("target_rows")+" VALUES(1,'one'),(2,'two')");
        var observed=DorisJdbcSupport.inspect(control,schema,"target_rows",20).table();
        assertEquals("LIST",observed.partitioning().kind());assertTrue(observed.distribution().isEmpty());
        assertEquals(0,observed.buckets());assertEquals(2,count("target_rows"));
    }
    @Test void nativeCloneUsesOverridesAndRejectsAnExistingLayoutMismatchBeforeWriting() throws Exception {
        sql(control,"CREATE TABLE "+table("source_rows")+"(id BIGINT NOT NULL,note VARCHAR(20)) ENGINE=OLAP UNIQUE KEY(id) COMMENT 'source' DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql(control,"INSERT INTO "+table("source_rows")+" VALUES(1,'low'),(101,'high')");
        var request=request(ProductTypeEnum.DORIS,url(),schema,"root","source_rows");
        success(request);
        assertLayout("chosen_rows","target comment");
        assertTrue(DorisJdbcSupport.inspect(control,schema,"chosen_rows",20).table().unique());
        assertEquals(2,count("chosen_rows"));
        var mapping=request.getSource().getFirst().getConfigMap().get("source_rows");
        var original=mapping.getDorisOptions();
        mapping.setDorisOptions(new DorisCreateTableOptions(original.partition(),"HASH",List.of("id"),"FIXED",3,1));
        try(var runtime=runtime()){
            var result=new MigrationService(request,ignored->{},null,runtime).run();
            assertFalse(result.getIfAllSuccess().get());
        }
        assertEquals(2,count("chosen_rows"));
        assertEquals(2,DorisJdbcSupport.inspect(control,schema,"chosen_rows",20).table().buckets());
    }
    @Test
    @EnabledIfSystemProperty(named="datahub.test.layout.pg.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
    void postgresMigrationUsesFinalNameCommentAndStructuredLayout() throws Exception {
        String pgUrl=System.getProperty("datahub.test.layout.pg.url");
        try(var postgres=DriverManager.getConnection(pgUrl,"postgres","")){
            sql(postgres,"CREATE SCHEMA "+schema);
            try {
                sql(postgres,"CREATE TABLE "+schema+".source_rows(id integer NOT NULL,note varchar(20))");
                sql(postgres,"INSERT INTO "+schema+".source_rows VALUES(1,'low'),(101,'high')");
                success(request(ProductTypeEnum.POSTGRESQL,pgUrl,schema,"postgres","source_rows"));
                assertLayout("chosen_rows","target comment");
                assertEquals(2,count("chosen_rows"));
            } finally {sql(postgres,"DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private MigrationRequest request(ProductTypeEnum product,String sourceUrl,String sourceSchema,String user,String sourceTable){
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(product);source.setUrl(sourceUrl);source.setUsername(user);source.setPassword("");
        source.setSourceSchema(sourceSchema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setMapperType("ALL");
        var mapping=new MapperConfig();mapping.setTargetTableName("chosen_rows");mapping.setTargetTableComment("target comment");mapping.setDorisOptions(DorisTableLayoutsTest.rangeOptions());
        source.setConfigMap(Map.of(sourceTable,mapping));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.DORIS);target.setUrl(url());target.setUsername("root");target.setPassword("");target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);target.setTablePrefix("legacy_");
        return request;
    }
    private void success(MigrationRequest request)throws Exception {try(var runtime=runtime()){var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());}}
    private DbSwitchRuntime runtime(){return new DbSwitchRuntime(DbSwitchProductRegistry.discover(),new DbSwitchOptions(128L*1024*1024,true,false,30,0,300000,30000,0),null);}
    private void assertLayout(String table,String comment)throws Exception {var observed=DorisJdbcSupport.inspect(control,schema,table,20).table();assertEquals(2,observed.buckets());assertEquals(List.of("id"),observed.distribution());assertEquals("RANGE",observed.partitioning().kind());assertEquals(2,observed.partitioning().partitions().size());assertEquals("tag.location.default: 1",observed.properties().get("replication_allocation"));assertEquals(comment,observed.comment());}
    private int count(String table)throws Exception {try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table(table))){assertTrue(rows.next());return rows.getInt(1);}}
    private String table(String name){return DorisNativeSchema.relation(schema,name);}
    private static void sql(Connection connection,String sql)throws Exception {try(var statement=connection.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);}}
    private static String url(){return System.getProperty("datahub.test.doris.url");}
}
