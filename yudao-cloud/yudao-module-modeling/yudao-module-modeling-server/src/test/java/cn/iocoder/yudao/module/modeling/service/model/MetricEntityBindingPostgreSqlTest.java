package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.sql.*;
import java.util.concurrent.*;
import java.util.*;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.BusinessConditionSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelServiceImpl;
import cn.iocoder.yudao.module.modeling.service.businesscondition.BusinessConditionServiceImpl;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 一次性本机 PostgreSQL 验证当前设计来源、实体归属及并发引用，不访问共享平台库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricEntityBindingPostgreSqlTest {
    private String url;
    private String user;
    private Connection connection;
    private long domainId;
    private long subjectId;
    private static final String FIELDS = """
        [{"fieldUid":"event","fieldName":"activity_id","dataType":"BIGINT","pkFlag":true},
         {"fieldUid":"value","fieldName":"amount","dataType":"DECIMAL","dataPrecision":18,"dataScale":2},
         {"fieldUid":"time","fieldName":"monitor_time","dataType":"TIME(6)"},
         {"fieldUid":"station","fieldName":"station_id","dataType":"BIGINT","dimensionId":8}]
        """;

    @BeforeAll void connect() throws Exception {
        url=System.getProperty("datahub.test.postgres.url");
        assumeTrue(url!=null && url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+$"),
                "需要显式提供本次一次性本机 PostgreSQL");
        user=System.getProperty("datahub.test.postgres.user","modeling_test");
        connection=DriverManager.getConnection(url,user,"");
    }

    @AfterAll void close() throws Exception {
        if(connection!=null) { execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE"); connection.close(); }
    }

    @Test void replayAndConcurrentEntityChangesPreserveTheCurrentSourceInvariant() throws Exception {
        seedCurrentSchema();
        execute("UPDATE dh_modeling_logic_model SET status='PUBLISHED' WHERE id=5");
        String atomic="INSERT INTO dh_modeling_indicator_model(id,code,name,indicator_type,data_domain_id,subject_domain_id,entity_id,data_freshness,source_model_id,value_field_uid,time_field_uid,aggregation,status) VALUES";
        execute(atomic+"(1,'atomic_one','原子一','ATOMIC',"+domainId+","+subjectId+",7,'1',5,'value','time','SUM','ACTIVE')");
        execute("INSERT INTO dh_modeling_business_condition(id,code,name,data_domain_id,subject_domain_id,entity_id,data_freshness,source_model_id,expression) VALUES(2,'condition_one','限定',"+domainId+","+subjectId+",7,'1',5,'amount>0')");
        String migration=Files.readString(ModelingMigrationTestSupport.repoFile("sql/postgresql/migrations/0236_modeling_remove-model-versions.sql"));
        execute(migration); execute(migration);
        execute("INSERT INTO dh_modeling_indicator_model(id,code,name,indicator_type,base_indicator_id,data_domain_id,subject_domain_id,entity_id,time_period,business_condition,status) VALUES(3,'derived_one','派生','DERIVED',1,"+domainId+","+subjectId+",7,'DAY','amount>0','ACTIVE')");
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT entity_id,source_model_id FROM dh_modeling_indicator_model WHERE id=3")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getLong("entity_id")).isEqualTo(7L);
            assertThat(rows.getObject("source_model_id")).isNull();
        }
        assertRejected("UPDATE dh_modeling_business_entity SET status=1 WHERE id=7");
        assertRejected("UPDATE dh_modeling_logic_model SET data_timeliness='2' WHERE id=5");
        assertRejected("DELETE FROM dh_modeling_logic_model_column WHERE model_id=5 AND field_uid='value'");
        assertRejected(atomic+"(9,'invalid_atomic','错误来源','ATOMIC',"+domainId+","+subjectId+",7,'2',5,'value','time','SUM','DRAFT')");
        assertRejected("INSERT INTO dh_modeling_business_condition(id,code,name,data_domain_id,subject_domain_id,entity_id,data_freshness,source_model_id,expression) VALUES(9,'invalid_condition','错误归属',"+domainId+",-999,7,'1',5,'amount>0')");
        execute("UPDATE dh_modeling_logic_model SET status='DRAFT',enabled=0 WHERE id=5");
        assertRejected(atomic+"(10,'draft_source','草稿来源','ATOMIC',"+domainId+","+subjectId+",7,'1',5,'value','time','SUM','DRAFT')");
        execute("UPDATE dh_modeling_logic_model SET status='PUBLISHED',enabled=1 WHERE id=5");
        assertThat(ModelingMigrationTestSupport.baselineBlock("-- Migration: 0236 ").strip()).isEqualTo(migration.replace("\r\n","\n").strip());
        execute("UPDATE dh_modeling_indicator_model SET deleted=1 WHERE indicator_type='DERIVED'; UPDATE dh_modeling_indicator_model SET deleted=1 WHERE indicator_type='ATOMIC'; UPDATE dh_modeling_business_condition SET deleted=1");
        try(var creator=DriverManager.getConnection(url,user,"");var changer=DriverManager.getConnection(url,user,"")) {
            creator.setAutoCommit(false);
            try(var statement=creator.createStatement()) {
                statement.execute("SET search_path TO \"datahub-center\"; "+atomic+"(12,'concurrent_atomic','并发引用','ATOMIC',"+domainId+","+subjectId+",7,'1',5,'value','time','SUM','DRAFT')");
            }
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                var attempted=new CountDownLatch(1);
                Future<String> result=worker.submit(() -> {
                    try(var statement=changer.createStatement()) {
                        statement.execute("SET search_path TO \"datahub-center\""); attempted.countDown();
                        statement.execute("UPDATE dh_modeling_business_entity SET status=1 WHERE id=7"); return "unexpected-success";
                    } catch(SQLException failure) { return failure.getSQLState(); }
                });
                assertThat(attempted.await(5,TimeUnit.SECONDS)).isTrue();
                assertThrows(TimeoutException.class,()->result.get(250,TimeUnit.MILLISECONDS));
                creator.commit();
                assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo("23514");
            } finally { creator.rollback(); worker.shutdownNow(); }
        }
    }

    @Test void realPublishAtomicConditionDerivedAndSummaryChainUsesTheActualDerivedStorageShape() throws Exception {
        seedCurrentSchema();
        var datasource=new DriverManagerDataSource(url+"?currentSchema=datahub-center",user,"");
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("metric-chain",new SpringManagedTransactionFactory(),datasource));
        var global=GlobalConfigUtils.getGlobalConfig(config); global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0").setKeyGenerators(List.of(new PostgreKeyGenerator()));
        for(Class<?> mapper:List.of(LogicModelMapper.class,LogicModelColumnMapper.class,IndicatorModelMapper.class,BusinessConditionMapper.class,BusinessEntityMapper.class)) config.addMapper(mapper);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var modelMapper=session.getMapper(LogicModelMapper.class);
        var indicators=session.getMapper(IndicatorModelMapper.class); var entities=session.getMapper(BusinessEntityMapper.class);
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        var hierarchy=mock(ModelHierarchyResolver.class); var permission=mock(SecurityFrameworkService.class);
        when(hierarchy.resolveLayerCode(any())).thenReturn("NONE");
        when(hierarchy.resolveTableType(eq("DETAIL"),any())).thenReturn("DETAIL");
        when(hierarchy.resolveClassificationStatus(any(),any(),any(),any(),any())).thenReturn("UNCLASSIFIED");
        when(permission.hasPermission("modeling:model:query")).thenReturn(true);
        var columns=new LogicModelColumnServiceImpl(); wire(columns,"columnMapper",session.getMapper(LogicModelColumnMapper.class));
        var publisher=new LogicModelServiceImpl();
        wire(publisher,"modelMapper",modelMapper); wire(publisher,"hierarchyResolver",hierarchy);
        wire(publisher,"relationMapper",mock(LogicRelationMapper.class)); wire(publisher,"columnService",columns);
        wire(publisher,"typeConfigValidator",new ModelTypeConfigValidator()); wire(publisher,"transactionTemplate",transactions);
        publisher.publishModel(5L,0L);
        var snapshot=modelMapper.selectById(5L);
        assertThat(snapshot.getEntityId()).isEqualTo(7L); assertThat(snapshot.getDataTimeliness()).isEqualTo("1");
        assertThat(snapshot.getDataDomainId()).isEqualTo(domainId); assertThat(snapshot.getSubjectDomainId()).isEqualTo(subjectId);

        var service=new IndicatorModelServiceImpl(); wire(service,"mapper",indicators); wire(service,"modelMapper",modelMapper);
        wire(service,"columns",columns); wire(service,"entityMapper",entities); wire(service,"hierarchy",hierarchy);
        wire(service,"security",permission); wire(service,"transactions",transactions);
        var atomic=new IndicatorModelSaveReqVO(); atomic.setCode(" RAIN_AMOUNT "); atomic.setName("降雨量"); atomic.setIndicatorType("ATOMIC");
        atomic.setDataDomainId(domainId); atomic.setSubjectDomainId(subjectId); atomic.setEntityId(7L); atomic.setDataFreshness("1");
        atomic.setSourceModelId(5L); atomic.setValueFieldUid("value"); atomic.setTimeFieldUid("time");
        atomic.setAggregation("SUM"); atomic.setResultDataType("DECIMAL"); atomic.setUnit("mm");
        Long atomicId=service.create(atomic); atomic.setId(atomicId); atomic.setExpectedRevision(0L); atomic.setStatus("ACTIVE"); service.update(atomic);
        assertThat(service.get(atomicId).getCode()).isEqualTo("RAIN_AMOUNT");
        var duplicateAtomic=cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(atomic,IndicatorModelSaveReqVO.class);
        duplicateAtomic.setCode("rain_amount");
        assertThat(assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.create(duplicateAtomic)).getCode())
                .isEqualTo(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_CODE_DUPLICATE.getCode());

        var conditions=new BusinessConditionServiceImpl(); wire(conditions,"mapper",session.getMapper(BusinessConditionMapper.class));
        wire(conditions,"modelMapper",modelMapper); wire(conditions,"entityMapper",entities);
        wire(conditions,"indicatorService",service); wire(conditions,"hierarchy",hierarchy); wire(conditions,"security",permission); wire(conditions,"transactions",transactions);
        var condition=new BusinessConditionSaveReqVO(); condition.setCode(" POSITIVE_RAIN "); condition.setName("有效降雨");
        condition.setDataDomainId(domainId); condition.setSubjectDomainId(subjectId); condition.setEntityId(7L); condition.setDataFreshness("1");
        condition.setSourceModelId(5L); condition.setConfigType("BASIC"); condition.setLogicMode("UNIFIED");
        condition.setExpression("amount > 0"); condition.setStatus("ACTIVE"); Long conditionId=conditions.create(condition);
        assertThat(indicators.countSourceModelReferences(5L)).isEqualTo(2L);
        assertThat(conditions.get(conditionId).getCode()).isEqualTo("POSITIVE_RAIN");
        condition.setCode("positive_rain");
        assertThat(assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->conditions.create(condition)).getCode())
                .isEqualTo(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.BUSINESS_CONDITION_CODE_DUPLICATE.getCode());
        var derived=new IndicatorModelSaveReqVO(); derived.setCode("day_rain"); derived.setName("日有效降雨量"); derived.setIndicatorType("DERIVED");
        derived.setDataDomainId(domainId); derived.setSubjectDomainId(subjectId); derived.setBaseIndicatorId(atomicId); derived.setTimePeriod("DAY");
        derived.setBusinessCondition(conditions.get(conditionId).getExpression()); Long derivedId=service.create(derived);
        derived.setId(derivedId); derived.setExpectedRevision(0L); derived.setBusinessCondition("amount > 0 AND amount < 100"); service.update(derived);
        var enable=new IndicatorModelStatusReqVO(); enable.setId(derivedId); enable.setExpectedRevision(1L); enable.setStatus("ACTIVE"); service.updateStatus(enable);
        var stored=indicators.selectById(derivedId);
        assertThat(stored.getSourceModelId()).isNull();
        assertThat(stored.getEntityId()).isEqualTo(7L); assertThat(stored.getDataFreshness()).isEqualTo("1");
        assertThat(stored.getBusinessCondition()).isEqualTo("amount > 0 AND amount < 100");
        var summary=new LogicModelDefinitionService(); wire(summary,"entities",entities); wire(summary,"indicators",indicators); wire(summary,"models",modelMapper); wire(summary,"columns",columns);
        wire(summary,"indicatorService",service); wire(publisher,"definitionService",summary);
        var generated=summary.summaryFields(domainId,subjectId,"1",List.of(8L));
        assertThat(generated).extracting(ModelFieldItem::getFieldName).containsExactly("ds","station_id","day_rain");
        assertThat(generated.get(2).getIndicatorId()).isEqualTo(derivedId); assertThat(generated.get(2).getSourceModelName()).isEqualTo("监测事实表");
        assertThat(generated.get(2).getAtomicIndicatorName()).isEqualTo("降雨量");

        var nonNumericValue=JsonUtils.parseArray(FIELDS,ModelFieldItem.class);
        nonNumericValue.get(1).setDataType("VARCHAR"); nonNumericValue.get(1).setDataLength(255);
        nonNumericValue.get(1).setDataPrecision(null); nonNumericValue.get(1).setDataScale(null);
        republishCurrentFields(publisher,modelMapper,columns,transactions,nonNumericValue);
        assertThat(summary.summaryFields(domainId,subjectId,"1",List.of(8L)))
                .extracting(ModelFieldItem::getFieldName).containsExactly("ds","station_id");

        republishCurrentFields(publisher,modelMapper,columns,transactions,JsonUtils.parseArray(FIELDS,ModelFieldItem.class));
        assertThat(summary.summaryFields(domainId,subjectId,"1",List.of(8L)))
                .extracting(ModelFieldItem::getFieldName).containsExactly("ds","station_id","day_rain");

        var nonTemporalTime=JsonUtils.parseArray(FIELDS,ModelFieldItem.class);
        nonTemporalTime.get(2).setDataType("VARCHAR"); nonTemporalTime.get(2).setDataLength(255);
        republishCurrentFields(publisher,modelMapper,columns,transactions,nonTemporalTime);
        assertThat(summary.summaryFields(domainId,subjectId,"1",List.of(8L)))
                .extracting(ModelFieldItem::getFieldName).containsExactly("ds","station_id");
    }

    private void republishCurrentFields(LogicModelServiceImpl publisher,LogicModelMapper models,
            LogicModelColumnService columns,TransactionTemplate transactions,List<ModelFieldItem> fields) {
        var current=models.selectById(5L);
        var draft=new ModelDraftSaveReqVO(); draft.setModelId(5L); draft.setExpectedRevision(current.getRevision());
        draft.setName(current.getName()); draft.setCode(current.getCode()); draft.setTableNameNotation(current.getTableNameNotation());
        draft.setModelStyle(current.getModelStyle()); draft.setTableCase(current.getTableCase());
        draft.setDataDomainId(current.getDataDomainId()); draft.setSubjectDomainId(current.getSubjectDomainId());
        draft.setTableType(current.getTableType()); draft.setEntityId(current.getEntityId());
        draft.setDataTimeliness(current.getDataTimeliness()); draft.setEnabled(current.getEnabled());
        draft.setTypeConfig(JsonUtils.parseObject(current.getTypeConfig(),ModelTypeConfigVO.class)); draft.setFields(fields);
        transactions.executeWithoutResult(transaction -> publisher.saveDraft(draft));
        var saved=models.selectById(5L);
        assertThat(saved.getStatus()).isEqualTo("DRAFT"); assertThat(saved.getPublishedTime()).isNull();
        assertThat(columns.listByModelId(5L)).extracting(ModelFieldItem::getFieldUid)
                .containsExactlyElementsOf(fields.stream().map(ModelFieldItem::getFieldUid).toList());
        assertThat(columns.listByModelId(5L)).extracting(ModelFieldItem::getDataType)
                .containsExactlyElementsOf(fields.stream().map(ModelFieldItem::getDataType).toList());
        publisher.publishModel(5L,saved.getRevision());
        assertThat(models.selectById(5L).getStatus()).isEqualTo("PUBLISHED");
    }

    private void seedCurrentSchema() throws Exception {
        execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        execute(Files.readString(ModelingMigrationTestSupport.repoFile("sql/postgresql/datahub-center.sql")));
        execute("SET search_path TO \"datahub-center\"");
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT data_domain_id,subject_domain_id FROM dh_modeling_business_entity WHERE status=0 AND deleted=0 ORDER BY id LIMIT 1")) {
            assertThat(rows.next()).isTrue(); domainId=rows.getLong(1); subjectId=rows.getLong(2);
        }
        execute("TRUNCATE dh_modeling_indicator_model,dh_modeling_business_condition,dh_modeling_model_materialized_log,dh_modeling_model_materialized,dh_modeling_deployment_attempt,dh_modeling_logic_relation,dh_modeling_logic_model_column,dh_modeling_logic_model,dh_modeling_business_entity_relation,dh_modeling_business_entity");
        execute("INSERT INTO dh_modeling_business_entity(id,data_domain_id,subject_domain_id,name,code,entity_type,entity_subtype,status) VALUES(7,"+domainId+","+subjectId+",'采集事件','COLLECT','ACTIVITY','EVENT',0),(8,"+domainId+","+subjectId+",'测站','STATION','OBJECT','NORMAL',0)");
        execute("INSERT INTO dh_modeling_logic_model(id,name,code,table_name_notation,table_type,entity_id,data_timeliness,data_domain_id,subject_domain_id,enabled,status,type_config) VALUES(5,'监测事实表','collect','DWD_COLLECT','DETAIL',7,'1',"+domainId+","+subjectId+",1,'DRAFT','{\"detail\":{\"organizationMode\":\"TRANSACTION\",\"grainDescription\":\"一条采集记录\",\"grainFields\":[\"activity_id\"],\"businessTimeField\":\"monitor_time\"}}')");
        execute("INSERT INTO dh_modeling_logic_model_column(model_id,field_uid,field_name,data_type,data_precision,data_scale,pk_flag,dimension_id) VALUES(5,'event','activity_id','BIGINT',19,0,1,NULL),(5,'value','amount','DECIMAL',18,2,0,NULL),(5,'time','monitor_time','TIME(6)',NULL,NULL,0,NULL),(5,'station','station_id','BIGINT',19,0,0,8)");
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT to_regclass('dh_modeling_logic_model_version')")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isNull();
        }
    }
    private static void wire(Object service,String field,Object value) { ReflectionTestUtils.setField(service,field,value); }

    private void assertRejected(String sql) {
        SQLException failure=assertThrows(SQLException.class,()->execute(sql)); assertThat(failure.getSQLState()).isEqualTo("23514");
    }
    private void execute(String sql) throws SQLException { try(var statement=connection.createStatement()) { statement.execute(sql); } }
}
