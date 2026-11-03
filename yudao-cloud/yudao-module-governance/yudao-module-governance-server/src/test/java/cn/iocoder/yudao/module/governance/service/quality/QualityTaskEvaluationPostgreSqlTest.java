package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实 PostgreSQL 验证互斥与正常/异常数据互补，覆盖 SQL 空值语义和精确数值边界。 */
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityTaskEvaluationPostgreSqlTest {
    @Test void generatedQueriesPartitionRowsAndKeepNumericBoundariesExact() throws Exception {
        var service=QualityTaskEvaluationServiceTest.service(); var registry=new AuditSqlRegistry();
        String table="quality_eval_"+UUID.randomUUID().toString().replace("-", "");
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), ""); var statement=connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE "+table+"(id integer, a text, b text, amount numeric)");
            statement.execute("INSERT INTO "+table+" VALUES (1,NULL,NULL,9007199254740993.000001),(2,'yes',NULL,9007199254740993.000002),(3,'yes','yes',9007199254740993.000003),(4,'no','no',NULL)");
            var rule=QualityTaskEvaluationServiceTest.request().getEvaluations().getFirst();
            rule.setStrategyKey("MUTUAL_EXCLUSION_VALIDATION"); rule.setColumnNames(List.of("a","b"));
            rule.setConfig(Map.of("condition","NOT_NULL"));
            var context=service.context(ProductTypeEnum.POSTGRESQL,"pg_temp."+table,rule); var generator=registry.getGenerator(rule.getStrategyKey());
            assertEquals(Set.of(3,4), ids(statement,generator.buildErrorDataSql(context,100)));
            assertEquals(Set.of(1,2), ids(statement,generator.buildNormalDataSql(context,100)));
            rule.setConfig(Map.of("condition","IS_TRUE","trueValues",List.of("yes","yes")));
            context=service.context(ProductTypeEnum.POSTGRESQL,"pg_temp."+table,rule);
            assertEquals(Set.of(3), ids(statement,generator.buildErrorDataSql(context,100)));
            assertEquals(Set.of(1,2,4), ids(statement,generator.buildNormalDataSql(context,100)));
            rule.setStrategyKey("NUMERIC_RANGE_VALIDATION"); rule.setColumnNames(List.of("amount"));
            rule.setConfig(Map.of("minValue","9007199254740993.000001","maxValue","9007199254740993.000002","includeBoundary",true,"ignoreNullValue",true));
            context=service.context(ProductTypeEnum.POSTGRESQL,"pg_temp."+table,rule); generator=registry.getGenerator(rule.getStrategyKey());
            assertEquals(Set.of(3),ids(statement,generator.buildErrorDataSql(context,100)));
            assertEquals(Set.of(1,2,4),ids(statement,generator.buildNormalDataSql(context,100)));
            rule.setStrategyKey("COMPOSITE_UNIQUENESS_VALIDATION"); rule.setColumnNames(List.of("a","b")); rule.setConfig(Map.of());
            statement.execute("INSERT INTO "+table+" VALUES (5,'yes','yes',0)");
            context=service.context(ProductTypeEnum.POSTGRESQL,"pg_temp."+table,rule); generator=registry.getGenerator(rule.getStrategyKey());
            assertEquals(Set.of(3,5),ids(statement,generator.buildErrorDataSql(context,100)));
            assertEquals(Set.of(1,2,4),ids(statement,generator.buildNormalDataSql(context,100)));
            statement.execute("UPDATE " + table + " SET a='[]-!' WHERE id=1");
            rule.setStrategyKey("CHARACTER_VALIDATION");rule.setColumnNames(List.of("a"));
            rule.setConfig(Map.of("allowedTypes",List.of("special")));
            context=service.context(ProductTypeEnum.POSTGRESQL,"pg_temp."+table,rule);generator=registry.getGenerator(rule.getStrategyKey());
            assertEquals(Set.of(1),ids(statement,generator.buildNormalDataSql(context,100)));
            assertEquals(Set.of(2,3,4,5),ids(statement,generator.buildErrorDataSql(context,100)));
        }
    }
    @Test void aggregatePersistsJsonbAndAllowsSameNameReplacementWithAtomicRollback() throws Exception {
        String url=System.getProperty("datahub.test.postgres.url"), user=System.getProperty("datahub.test.postgres.user","dataforge_test");
        String schema="qualityaggregate_"+UUID.randomUUID().toString().replace("-", "");
        try(var security=org.mockito.Mockito.mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class);
            var control=DriverManager.getConnection(url,user,"");var sql=control.createStatement()) {
            security.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(10L);
            sql.execute("CREATE SCHEMA "+schema);sql.execute("SET search_path TO "+schema);
            try {
                cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.copyCurrentTables(control,schema,List.of(
                        "dh_governance_quality_task_evaluation","dh_governance_quality_check_object","dh_governance_quality_task_object"));
                var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+"?currentSchema="+schema,user,"");
                var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
                var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
                com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
                config.setEnvironment(new org.apache.ibatis.mapping.Environment("aggregate",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));
                config.addMapper(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskEvaluationMapper.class);
                config.addMapper(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper.class);
                config.addMapper(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskObjectMapper.class);
                var session=new org.mybatis.spring.SqlSessionTemplate(new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config));
                var service=QualityTaskEvaluationServiceTest.service();
                org.springframework.test.util.ReflectionTestUtils.setField(service,"evaluations",session.getMapper(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskEvaluationMapper.class));
                org.springframework.test.util.ReflectionTestUtils.setField(service,"objects",session.getMapper(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper.class));
                org.springframework.test.util.ReflectionTestUtils.setField(service,"relations",session.getMapper(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskObjectMapper.class));
                var request=QualityTaskEvaluationServiceTest.request();
                tx.executeWithoutResult(status->service.save(7L,request));
                var detail=new cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskRespVO();detail.setId(7L);service.fill(detail);
                assertEquals(List.of("digit"),detail.getEvaluations().getFirst().getConfig().get("allowedTypes"));
                assertEquals("当前源库名称",detail.getObjects().getFirst().getDatasourceConnName());
                Long firstVersion=detail.getEvaluations().getFirst().getId();
                request.setObjects(detail.getObjects());request.setEvaluations(detail.getEvaluations());
                request.getEvaluations().getFirst().setId(null);
                tx.executeWithoutResult(status->service.save(7L,request));service.fill(detail);
                Long secondVersion=detail.getEvaluations().getFirst().getId();assertNotEquals(firstVersion,secondVersion);
                assertEquals(List.of("digit"),detail.getEvaluations().getFirst().getConfig().get("allowedTypes"));
                assertEquals(List.of("value"),detail.getEvaluations().getFirst().getColumnNames());
                request.setEvaluations(detail.getEvaluations());
                assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{service.save(7L,request);throw new IllegalStateException("模拟后续写入失败");}));
                service.fill(detail);assertEquals(secondVersion,detail.getEvaluations().getFirst().getId());
                try(var rows=sql.executeQuery("SELECT count(*),count(*) FILTER(WHERE deleted=0) FROM dh_governance_quality_task_evaluation")) {
                    assertTrue(rows.next());assertEquals(2,rows.getInt(1));assertEquals(1,rows.getInt(2));
                }
            } finally {sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }

    private Set<Integer> ids(java.sql.Statement statement,String sql) throws Exception {
        Set<Integer> result=new HashSet<>(); try(var rows=statement.executeQuery(sql)){while(rows.next()) result.add(rows.getInt("id"));} return result;
    }
}
