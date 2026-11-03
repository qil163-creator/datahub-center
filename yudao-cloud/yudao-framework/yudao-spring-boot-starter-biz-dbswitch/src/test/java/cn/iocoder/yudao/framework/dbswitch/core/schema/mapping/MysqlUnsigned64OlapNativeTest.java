package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** MySQL's entire unsigned BIGINT domain fits the native Doris/StarRocks LARGEINT codec. */
@Timeout(180)
@EnabledIfSystemProperty(named="datahub.test.mysql.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.+")
class MysqlUnsigned64OlapNativeTest {
    @Test @EnabledIfSystemProperty(named="datahub.test.doris.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    void dorisCopiesUnsigned64ExtremaAndRefusesUnselectedReverseDomain() throws Exception { verify(DORIS); }

    @Test @EnabledIfSystemProperty(named="datahub.test.starrocks.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    void starrocksCopiesUnsigned64ExtremaAndRefusesUnselectedReverseDomain() throws Exception { verify(STARROCKS); }

    private void verify(ProductTypeEnum target) throws Exception {
        String schema="uint64_"+UUID.randomUUID().toString().replace("-","");
        String sourceTable=schema;
        try(var mysql=open(MYSQL);var olap=open(target);
            var runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),new DbSwitchOptions(65536,false,false,20,0,0,250,0),null)) {
            String mysqlSchema=mysql.getCatalog();assertNotNull(mysqlSchema);
            execute(mysql,"CREATE TABLE `"+sourceTable+"`(id INT PRIMARY KEY,v BIGINT UNSIGNED)");
            try {
                execute(mysql,"INSERT INTO `"+sourceTable+"` VALUES(1,0),(2,9223372036854775808),(3,18446744073709551615),(4,NULL)");
                execute(olap,"CREATE DATABASE "+schema);
                try {
                    var metadata=runtime.metadata(dataSource(MYSQL),MYSQL);
                    var columns=metadata.queryTableColumnMetaWithNativeEvidence(mysqlSchema,sourceTable);
                    var preview=runtime.products().typeMappings().plan(target,columns,metadata.queryTablePrimaryKeys(mysqlSchema,sourceTable),LossyColumnAcceptance.rejectAll());
                    assertEquals(TypeFidelity.EXACT,preview.column("v").fidelity());
                    assertEquals("largeint",preview.column("v").targetNativeType());
                    var request=request(MYSQL,mysqlSchema,sourceTable,target,schema,"copied");
                    request.setTypeMappingAcknowledgements(preview.columns().stream().filter(c->c.fidelity()==TypeFidelity.LOSSY)
                            .map(c->LossyMappingAcknowledgement.forColumn(schema,"copied",c)).toList());
                    var result=new MigrationService(request,ignored->{},null,runtime).run();
                    assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
                    List<String> expected=Arrays.asList("0","9223372036854775808","18446744073709551615",null);
                    assertEquals(expected,values(olap,schema+".copied"));

                    // LARGEINT includes negative values and more than 64 bits. No implicit unsigned target policy exists.
                    var reverse=request(target,schema,"copied",MYSQL,mysqlSchema,sourceTable);
                    reverse.getTarget().setTargetDrop(true);reverse.getTarget().setExclusiveTargetDdlOwnership(true);
                    reverse.getTarget().setTargetTruncateData(true);
                    var rejected=new MigrationService(reverse,ignored->{},null,runtime).run();
                    assertFalse(rejected.getIfAllSuccess().get());
                    assertEquals(TypeFidelity.UNSUPPORTED,rejected.getTableResultList().getFirst().getTypeMappingPlan().column("v").fidelity());
                    assertEquals(expected,values(mysql,"`"+sourceTable+"`"));
                } finally {execute(olap,"DROP DATABASE IF EXISTS "+schema+" FORCE");}
            } finally {execute(mysql,"DROP TABLE IF EXISTS `"+sourceTable+"`");}
        }
    }

    private static MigrationRequest request(ProductTypeEnum sourceProduct,String sourceSchema,String sourceTable,
                                            ProductTypeEnum targetProduct,String targetSchema,String targetTable) {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct);source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceProduct));source.setUsername(user(sourceProduct));source.setPassword(password(sourceProduct));
        source.setSourceSchema(sourceSchema);source.setSourceIncludes(sourceTable);source.setPoolSize(1);source.setFetchSize(4);
        source.setRegexTableMapper(List.of(new PatternMapper("^"+sourceTable+"$",targetTable)));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(targetProduct);target.setDriverClassName(targetProduct.getDriveClassName());
        target.setUrl(url(targetProduct));target.setUsername(user(targetProduct));target.setPassword(password(targetProduct));
        target.setTargetSchema(targetSchema);target.setPoolSize(1);target.setWriterEngineInsert(true);return request;
    }
    private static List<String> values(Connection connection,String table)throws Exception {
        var values=new ArrayList<String>();try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT v FROM "+table+" ORDER BY id")) {
            while(rows.next())values.add(rows.getString(1));
        }return values;
    }
    private static void execute(Connection connection,String sql)throws Exception {try(var statement=connection.createStatement()){statement.setQueryTimeout(20);statement.execute(sql);}}
    private static String property(ProductTypeEnum product,String suffix,String fallback){return System.getProperty("datahub.test."+product.name().toLowerCase(java.util.Locale.ROOT)+'.'+suffix,fallback);}
    private static String url(ProductTypeEnum product){return property(product,"url",null);}
    private static String user(ProductTypeEnum product){return property(product,"user","root");}
    private static String password(ProductTypeEnum product){return property(product,"password","");}
    private static DriverManagerDataSource dataSource(ProductTypeEnum product){return new DriverManagerDataSource(url(product),user(product),password(product));}
    private static Connection open(ProductTypeEnum product)throws Exception{return DriverManager.getConnection(url(product),user(product),password(product));}
}
