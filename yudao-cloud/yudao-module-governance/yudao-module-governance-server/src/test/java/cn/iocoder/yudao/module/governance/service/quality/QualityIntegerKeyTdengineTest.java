package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.*;
import java.time.LocalDateTime;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;

@EnabledIfSystemProperty(named="datahub.test.tdengine.url",matches="jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named="datahub.test.tdengine.bundle-manifest",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_TDENGINE_TEST_PASSWORD",matches=".+")
class QualityIntegerKeyTdengineTest {
    private Connection connection;
    private Statement sql;
    private HikariDataSource pool;
    private AnnotationConfigApplicationContext application;
    private MasterDatabaseResolver resolver;
    private MasterDatabaseResolver.ConnectionInfo route;
    private String schema;
    private final long timestamp=System.currentTimeMillis();

    @BeforeEach void setup() throws Exception {
        schema="q11td_"+UUID.randomUUID().toString().replace("-","");
        Path manifest=Path.of(System.getProperty("datahub.test.tdengine.bundle-manifest")).toRealPath();
        var driver=JsonUtils.parseObject(Files.readString(manifest),DbSwitchProperties.Driver.class);
        var dto=new DatabaseConnectionRespDTO();dto.setId(4L);dto.setProductType("TDENGINE");dto.setSchema(schema);
        dto.setJdbcUrl(System.getProperty("datahub.test.tdengine.url"));dto.setUsername("root");
        dto.setPassword(System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD"));dto.setUsageType(1);dto.setConfigurationRevision("fixture-revision");
        var api=mock(DatabaseApi.class);when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        var properties=new HashMap<String,Object>();String prefix="yudao.dbswitch.drivers.TDENGINE.";
        properties.put(prefix+"bundle-root",driver.getBundleRoot());
        properties.put(prefix+"driver-class-name",driver.getDriverClassName());
        for(int i=0;i<driver.getArtifacts().size();i++){
            properties.put(prefix+"artifacts["+i+"].file",driver.getArtifacts().get(i).getFile());
            properties.put(prefix+"artifacts["+i+"].sha256",driver.getArtifacts().get(i).getSha256());
        }
        application=new AnnotationConfigApplicationContext();
        application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("tdengine-fixture",properties));
        application.registerBean(DatabaseApi.class,()->api);
        application.register(YudaoDbSwitchAutoConfiguration.class,MasterDatabaseResolver.class);
        application.refresh();resolver=application.getBean(MasterDatabaseResolver.class);
        route=resolver.resolve(4L,QUALITY_CHECK_OBJECT_NOT_EXISTS);assertNotEquals(dto.getConfigurationRevision(),route.getConfigurationRevision());
        pool=resolver.createDataSource(route);connection=pool.getConnection();
        sql=connection.createStatement();sql.setQueryTimeout(10);
        sql.execute("CREATE DATABASE "+schema+" VGROUPS 1 BUFFER 8");create();
    }
    @AfterEach void cleanup() throws Exception {
        try {if(sql!=null&&schema!=null)sql.execute("DROP DATABASE IF EXISTS "+schema);}
        finally {
            try {if(sql!=null)sql.close();if(connection!=null)connection.close();}
            finally {try {if(pool!=null)pool.close();} finally {if(application!=null)application.close();}}
        }
    }
    private String table(){return schema+".sample";}
    private void create() throws SQLException {sql.execute("CREATE TABLE "+table()+"(ts TIMESTAMP,id BIGINT,part INT,org_code INT,amount BIGINT)");}
    private void upsert(int slot,long id,int part,int org,String amount) throws SQLException {
        sql.execute("INSERT INTO "+table()+" VALUES("+(timestamp+slot)+","+id+","+part+","+org+","+amount+")");
    }

    @ParameterizedTest @CsvSource({"false,ALL","false,ORG","true,ALL","true,ORG"})
    void nativeRepairSucceedsButReplacementTupleSwapAndInstitutionMoveDoNot(boolean composite,String scope) throws Exception {
        upsert(1,9007199254740992L,11,9,"NULL");upsert(2,9007199254740993L,12,9,"1");upsert(3,3,13,10,"NULL");
        var before=run(10,composite);
        upsert(1,9007199254740992L,11,9,"0");
        var after=run(20,composite);var result=compare(before,after,scope);
        assertTrue(result.comparable(),result.reason());assertEquals("ORG".equals(scope)?"PASS":"FAIL",result.resultType());
        var evidence=JsonUtils.parseObject(after.audit().getReadEvidence(),QualityReadEvidence.class);
        assertEquals("TDENGINE",evidence.getProductType());assertNotNull(evidence.getRelationFingerprint());
        upsert(1,9007199254740994L,11,9,"1");
        assertReason("ROW_POPULATION_CHANGED",compare(before,run(21,composite),scope));
        upsert(1,9007199254740992L,11,9,"1");
        if(composite){
            upsert(1,9007199254740992L,12,9,"1");upsert(2,9007199254740993L,11,9,"1");
            assertReason("ROW_POPULATION_CHANGED",compare(before,run(22,true),scope));
            upsert(1,9007199254740992L,11,9,"1");upsert(2,9007199254740993L,12,9,"1");
        }
        if("ORG".equals(scope)){
            upsert(1,9007199254740992L,11,10,"1");upsert(3,3,13,9,"1");
            assertReason("ROW_POPULATION_CHANGED",compare(before,run(23,composite),scope));
        }
        sql.execute("DROP TABLE "+table());create();
        upsert(1,9007199254740992L,11,9,"1");upsert(2,9007199254740993L,12,9,"1");upsert(3,3,13,10,"1");
        assertReason("RELATION_CHANGED",compare(before,run(24,composite),scope));
    }

    @Test void oldIncompleteEvidenceAndUnverifiedRuleAreNotUpgraded() throws Exception {
        upsert(1,1,1,9,"NULL");var before=run(10,false);upsert(1,1,1,9,"1");var after=run(20,false);
        for(String scope:List.of("ALL","ORG"))assertTrue(compare(before,after,scope).comparable());
        String original=before.audit().getReadEvidence();
        var old=JsonUtils.parseObject(original,QualityReadEvidence.class);old.setRelationFingerprint(null);
        before.audit().setReadEvidence(JsonUtils.toJsonString(old));
        for(String scope:List.of("ALL","ORG"))assertFalse(compare(before,after,scope).comparable());
        before.audit().setReadEvidence(original);
        for(var run:List.of(before,after)){
            var definition=JsonUtils.parseObject(run.instance().getDefinitionSnapshot(),QualityTaskExecutor.GateDefinition.class);
            definition.getRules().getFirst().setStrategyKey("LENGTH_VALIDATION");
            run.instance().setDefinitionSnapshot(JsonUtils.toJsonString(definition));
        }
        assertReason("RULE_DIALECT_UNVERIFIED",compare(before,after,"ALL"));
    }

    @Test void actualObjectEntryUsesPinnedDriverAndClosesItsOwnPool() throws Exception {
        upsert(1,1,1,9,"NULL");
        var pools=new ArrayList<HikariDataSource>();var tracked=spy(resolver);
        doAnswer(call->{var created=resolver.createDataSource(call.getArgument(0));pools.add(created);return created;}).when(tracked).createDataSource(any());
        var executor=executor();ReflectionTestUtils.setField(executor,"databaseResolver",tracked);
        for(String expected:List.of("FAIL","PASS")){
            var results=new ArrayList<QualityAuditLogDO>();
            QualityRuntimeFixture.executeObject(executor,30L,task(),route,object(),List.of(rule(true)),results);
            assertEquals(1,results.size());var audit=results.getFirst();assertEquals(expected,audit.getResultType(),audit.getErrorDetail());
            var evidence=JsonUtils.parseObject(audit.getReadEvidence(),QualityReadEvidence.class);
            assertNotNull(evidence.getRelationFingerprint());assertTrue(evidence.getKeyScope().getComplete());
            assertEquals(AuditKeySnapshot.TDENGINE_COMPOSITE_ALGORITHM,evidence.getKeyScope().getAlgorithm());
            assertEquals(AuditKeySnapshot.TDENGINE_INTEGER_GROUP_ENCODING,evidence.getOrgScope().getGroupEncoding());
            assertTrue(pools.stream().allMatch(HikariDataSource::isClosed));assertFalse(connection.isClosed());
            upsert(1,1,1,9,"1");
        }
        assertEquals(2,pools.size());
    }

    private QualityTaskDO task(){return QualityTaskDO.builder().id(1L).orgField("org_code").build();}
    private QualityCheckObjectDO object(){return QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();}
    private QualityRuleDO rule(boolean composite){return QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
            .paramSchema(composite?"{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}":"{\"column\":\"amount\",\"pkColumn\":\"id\"}").collectIssueDetail(0).build();}
    private QualityTaskExecutor executor(){var executor=QualityRuntimeFixture.executor();ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor,"auditLogMapper",mock(QualityAuditLogMapper.class));return executor;}
    private record Run(QualityTaskInstanceDO instance,QualityAuditLogDO audit){}
    private Run run(long id,boolean composite){
        var definition=new QualityTaskExecutor.GateDefinition();definition.setTask(task());definition.setObjects(List.of(object()));definition.setRules(List.of(rule(composite)));
        var instance=QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS").definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        QualityAuditLogDO audit=QualityRuntimeFixture.executeRule(executor(),id,task(),connection,route,object(),rule(composite));
        assertNotNull(audit);audit.setId(id*10);instance.setEndTime(LocalDateTime.now());
        assertNotEquals("ERROR",audit.getResultType(),audit.getErrorDetail());return new Run(instance,audit);
    }
    private QualityRecheckEvidence.Comparison compare(Run before,Run after,String scope){
        var issue=QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L).instanceId(before.instance().getId()).auditLogId(before.audit().getId())
                .tableName(table()).checkField("amount").statScope(scope).regionCode("ORG".equals(scope)?"9":null).issueCount("ORG".equals(scope)?1L:before.audit().getFailCount()).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(),issue,before.instance(),before.audit(),after.instance(),after.audit(),before.instance().getEndTime());
    }
    private static void assertReason(String reason,QualityRecheckEvidence.Comparison result){assertFalse(result.comparable());assertEquals(reason,result.evidence().get("reasonCode"));}
}
