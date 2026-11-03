package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.tidb.url",matches="jdbc:mysql://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationCursorTidbTest {
    @ParameterizedTest @CsvSource({"TIDB,POSTGRESQL","POSTGRESQL,TIDB"})
    void actualServiceAndHandlerMoveThenResumeWithProductBoundCommitAcknowledgement(ProductTypeEnum sourceProduct,ProductTypeEnum targetProduct)throws Exception {
        String schema="tidb_service_"+UUID.randomUUID().toString().replace("-","");
        try(var runtime=DbSwitchRuntime.standalone();var source=open(sourceProduct);var target=open(targetProduct)) {
            try {
                sql(source,"CREATE "+(sourceProduct==ProductTypeEnum.TIDB?"DATABASE ":"SCHEMA ")+schema);
                sql(target,"CREATE "+(targetProduct==ProductTypeEnum.TIDB?"DATABASE ":"SCHEMA ")+schema);
                sql(source,"CREATE TABLE "+schema+".source(stamp decimal(38,8) NOT NULL,id bigint PRIMARY KEY,value varchar(80))");
                sql(target,"CREATE TABLE "+schema+".target(stamp decimal(38,8) NOT NULL,id bigint PRIMARY KEY,value varchar(80))");
                sql(source,"INSERT INTO "+schema+".source VALUES(10,2,'first'),(10,3,'second'),(11,4,'next')");
                var pin=sourceProduct==ProductTypeEnum.TIDB?cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration.initialize(source,schema,5):null;
                var request=new MigrationRequest();var input=new SourceDataSourceProperties();input.setSourceProductType(sourceProduct);
                input.setDriverClassName(sourceProduct.getDriveClassName());input.setUrl(url(sourceProduct));input.setUsername(user(sourceProduct));input.setPassword(password(sourceProduct));
                input.setPoolSize(sourceProduct==ProductTypeEnum.TIDB?2:1);input.setFetchSize(2);input.setSourceSchema(schema);input.setSourceIncludes("source");input.setIncreaseColumnName("stamp");
                input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");input.setRegexTableMapper(List.of(new PatternMapper("^source$","target")));request.getSource().add(input);
                var output=request.getTarget();output.setTargetProductType(targetProduct);output.setDriverClassName(targetProduct.getDriveClassName());output.setUrl(url(targetProduct));
                output.setUsername(user(targetProduct));output.setPassword(password(targetProduct));output.setPoolSize(1);output.setTargetSchema(schema);output.setChangeDataSync(true);output.setChangeDataSyncType("INCREASE_COLUMN");
                var checkpoint=new AtomicReference<CursorCheckpoint>();output.setCursorRunBindings(Map.of("source",new CursorRunBinding("b".repeat(64),null,next->{checkpoint.set(next);return true;},pin)));
                var first=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(first.getIfAllSuccess().get());assertEquals(3,first.getTotalRowCount().get());assertTrue(checkpoint.get().complete());
                assertEquals(sourceProduct,checkpoint.get().sourceProduct());assertEquals(targetProduct,checkpoint.get().targetProduct());
                assertEquals(targetProduct==ProductTypeEnum.TIDB?CommitAcknowledgement.JDBC_COMMIT_RETURNED:CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,checkpoint.get().lastCommitAcknowledgement());
                sql(source,"INSERT INTO "+schema+".source VALUES(11,1,'late same watermark')");
                output.setCursorRunBindings(Map.of("source",new CursorRunBinding("b".repeat(64),checkpoint.get(),next->{checkpoint.set(next);return true;},pin)));
                assertTrue(new MigrationService(request,ignored->{},null,runtime).run().getIfAllSuccess().get());
                try(var statement=target.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM "+schema+".target")){assertTrue(rows.next());assertEquals(4,rows.getLong(1));}
            } finally {
                sql(source,"DROP "+(sourceProduct==ProductTypeEnum.TIDB?"DATABASE ":"SCHEMA ")+"IF EXISTS "+schema+(sourceProduct==ProductTypeEnum.TIDB?"":" CASCADE"));
                sql(target,"DROP "+(targetProduct==ProductTypeEnum.TIDB?"DATABASE ":"SCHEMA ")+"IF EXISTS "+schema+(targetProduct==ProductTypeEnum.TIDB?"":" CASCADE"));
            }
        }
    }
    private static String prefix(ProductTypeEnum product){return "datahub.test."+(product==ProductTypeEnum.TIDB?"tidb":"postgres");}

    @Test void twoConnectionSourcePoolSerializesMultipleTablesWithoutFreshProbeStarvation()throws Exception {
        String schema="tidb_pool_"+UUID.randomUUID().toString().replace("-","");
        try(var runtime=DbSwitchRuntime.standalone();var source=open(ProductTypeEnum.TIDB);var target=open(ProductTypeEnum.POSTGRESQL)) {
            try {
                sql(source,"CREATE DATABASE "+schema);sql(target,"CREATE SCHEMA "+schema);
                var pin=cn.iocoder.yudao.framework.dbswitch.core.util.TidbSourceGeneration.initialize(source,schema,5);
                var sourceSpec=new cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties.SourceGeneration();
                sourceSpec.setProduct(ProductTypeEnum.TIDB);sourceSpec.setRestoreRotationRequired(true);sourceSpec.setSchema(pin.schema());sourceSpec.setInstanceId(pin.instanceId());sourceSpec.setGeneration(pin.generation());
                sourceSpec.setTargetFingerprint(cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.TIDB,url(ProductTypeEnum.TIDB),user(ProductTypeEnum.TIDB)));
                var deployed=new cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations(Map.of("tidb",sourceSpec))
                        .find(ProductTypeEnum.TIDB,url(ProductTypeEnum.TIDB),user(ProductTypeEnum.TIDB)).orElseThrow();
                Map<String,CursorRunBinding> bindings=new LinkedHashMap<>();Map<String,CursorCheckpoint> receipts=new java.util.concurrent.ConcurrentHashMap<>();
                for(String table:List.of("source_a","source_b","source_c")) {
                    sql(source,"CREATE TABLE "+schema+"."+table+"(stamp bigint NOT NULL,id bigint PRIMARY KEY)");
                    sql(target,"CREATE TABLE "+schema+"."+table+"(stamp bigint NOT NULL,id bigint PRIMARY KEY)");
                    sql(source,"INSERT INTO "+schema+"."+table+" VALUES(10,1),(10,2),(11,3)");
                    bindings.put(table,new CursorRunBinding("c".repeat(64),null,next->{receipts.put(table,next);return true;},deployed));
                }
                var request=new MigrationRequest();var input=new SourceDataSourceProperties();request.getSource().add(input);
                input.setSourceProductType(ProductTypeEnum.TIDB);input.setDriverClassName(ProductTypeEnum.TIDB.getDriveClassName());input.setUrl(url(ProductTypeEnum.TIDB));input.setUsername(user(ProductTypeEnum.TIDB));input.setPassword(password(ProductTypeEnum.TIDB));
                input.setSourceSchema(schema);input.setSourceIncludes("source_a,source_b,source_c");input.setIncreaseColumnName("stamp");input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");input.setFetchSize(1);input.setPoolSize(2);input.setConnectionTimeout(1000L);
                var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());output.setUrl(url(ProductTypeEnum.POSTGRESQL));output.setUsername(user(ProductTypeEnum.POSTGRESQL));output.setPassword(password(ProductTypeEnum.POSTGRESQL));
                output.setTargetSchema(schema);output.setPoolSize(3);output.setChangeDataSync(true);output.setChangeDataSyncType("INCREASE_COLUMN");output.setCursorRunBindings(bindings);
                var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get());assertEquals(9,result.getTotalRowCount().get());assertEquals(3,receipts.size());
                assertTrue(receipts.values().stream().allMatch(CursorCheckpoint::complete));
            }finally{sql(source,"DROP DATABASE IF EXISTS "+schema);sql(target,"DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
        }
    }
    private static String url(ProductTypeEnum product){return System.getProperty(prefix(product)+".url");}
    private static String user(ProductTypeEnum product){return System.getProperty(prefix(product)+".user",product==ProductTypeEnum.TIDB?"root":"dataforge_test");}
    private static String password(ProductTypeEnum product){return System.getProperty(prefix(product)+".password","");}
    private static Connection open(ProductTypeEnum product)throws SQLException{return DriverManager.getConnection(url(product),user(product),password(product));}
    private static void sql(Connection connection,String sql)throws SQLException{try(var statement=connection.createStatement()){statement.execute(sql);}}
}
