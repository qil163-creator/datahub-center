package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Proves the ordinary service/handler route reaches the product cursor and native target UPSERT. */
@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.mysql.url",matches="jdbc:mysql://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.mariadb.url",matches="jdbc:mariadb://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.postgres.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationCursorMysqlMariaDbTest {
    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class,names={"MYSQL","MARIADB"})
    void serviceMovesAndResumesThroughActualTypedCursorHandler(ProductTypeEnum product) throws Exception {
        String sourcePrefix="datahub.test."+product.name().toLowerCase(Locale.ROOT);
        String schema="cursor_service_"+UUID.randomUUID().toString().replace("-", "");
        String sourceUrl=System.getProperty(sourcePrefix+".url"), targetUrl=System.getProperty("datahub.test.postgres.url");
        String sourceUser=System.getProperty(sourcePrefix+".user","root"), targetUser=System.getProperty("datahub.test.postgres.user","dataforge_test");
        String sourcePassword=System.getProperty(sourcePrefix+".password",""), targetPassword=System.getProperty("datahub.test.postgres.password","");
        try(var runtime=DbSwitchRuntime.standalone();
            var source=DriverManager.getConnection(sourceUrl,sourceUser,sourcePassword);
            var target=DriverManager.getConnection(targetUrl,targetUser,targetPassword)) {
            try {
                sql(source,"CREATE DATABASE "+schema);sql(target,"CREATE SCHEMA "+schema);
                sql(source,"CREATE TABLE "+schema+".source(stamp decimal(38,8) NOT NULL,id bigint PRIMARY KEY,value varchar(80)) ENGINE=InnoDB");
                sql(target,"CREATE TABLE "+schema+".target(stamp numeric(38,8) NOT NULL,id bigint PRIMARY KEY,value varchar(80))");
                sql(source,"INSERT INTO "+schema+".source VALUES(10,2,'first'),(10,3,'second'),(11,4,'next')");
                var request=new MigrationRequest();var input=new SourceDataSourceProperties();
                input.setSourceProductType(product);input.setDriverClassName(product.getDriveClassName());
                input.setUrl(sourceUrl);input.setUsername(sourceUser);input.setPassword(sourcePassword);input.setPoolSize(1);
                input.setSourceSchema(schema);input.setSourceIncludes("source");input.setFetchSize(2);
                input.setIncreaseColumnName("stamp");input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
                input.setRegexTableMapper(List.of(new PatternMapper("^source$","target")));request.getSource().add(input);
                var output=request.getTarget();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);
                output.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());output.setPoolSize(1);
                output.setUrl(targetUrl);output.setUsername(targetUser);output.setPassword(targetPassword);output.setTargetSchema(schema);
                output.setChangeDataSync(true);output.setChangeDataSyncType("INCREASE_COLUMN");
                var checkpoint=new AtomicReference<CursorCheckpoint>();
                output.setCursorRunBindings(Map.of("source",new CursorRunBinding("b".repeat(64),null,next->{checkpoint.set(next);return true;})));
                var first=new MigrationService(request,ignored->{},null,runtime).run();
                assertTrue(first.getIfAllSuccess().get(),()->first.getTableResultList().toString());
                assertEquals(3,first.getTotalRowCount().get());assertTrue(checkpoint.get().complete());
                assertEquals(product,checkpoint.get().sourceProduct());
                assertEquals(CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED,checkpoint.get().lastCommitAcknowledgement());
                sql(source,"INSERT INTO "+schema+".source VALUES(11,1,'late equal watermark')");
                output.setCursorRunBindings(Map.of("source",new CursorRunBinding("b".repeat(64),checkpoint.get(),next->{checkpoint.set(next);return true;})));
                var resumed=new MigrationService(request,ignored->{},null,runtime).run();
                assertTrue(resumed.getIfAllSuccess().get(),()->resumed.getTableResultList().toString());
                try(var statement=target.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM "+schema+".target")) {
                    assertTrue(rows.next());assertEquals(4,rows.getLong(1));
                }
            } finally {sql(source,"DROP DATABASE IF EXISTS "+schema);sql(target,"DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
        }
    }
    private static void sql(Connection connection,String sql) throws SQLException {try(var statement=connection.createStatement()){statement.execute(sql);}}
}
