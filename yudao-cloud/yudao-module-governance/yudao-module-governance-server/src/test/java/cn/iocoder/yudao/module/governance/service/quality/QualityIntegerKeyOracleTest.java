package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.quality.core.audit.*;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real task entry using the existing application classpath's official Oracle driver, not a standalone pin. */
@EnabledIfSystemProperty(named="datahub.test.oracle.url",matches="jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
class QualityIntegerKeyOracleTest {
    private HikariDataSource pool;
    private Connection admin;
    private MasterDatabaseResolver resolver;
    private MasterDatabaseResolver.ConnectionInfo route;
    private String schema, reader, password;
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);schema="Q28_"+suffix;reader="QR28_"+suffix;password="Q28x"+UUID.randomUUID().toString().replace("-","");
        Class.forName("oracle.jdbc.OracleDriver");
        var dto=new DatabaseConnectionRespDTO();dto.setId(4L);dto.setProductType("ORACLE");dto.setSchema(schema);dto.setUsageType(1);
        dto.setJdbcUrl(System.getProperty("datahub.test.oracle.url"));dto.setUsername("SYSTEM");dto.setPassword(System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));dto.setConfigurationRevision("fixture-v1");
        var api=mock(DatabaseApi.class);when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class,()->api).withBean(MasterDatabaseResolver.class)
                .run(context->{assertNull(context.getStartupFailure());resolver=context.getBean(MasterDatabaseResolver.class);
                    pool=resolver.createDataSource(resolver.resolve(4L,QUALITY_CHECK_OBJECT_NOT_EXISTS));admin=pool.getConnection();});
        execute("CREATE USER "+schema+" IDENTIFIED BY "+password+" DEFAULT TABLESPACE CODEX_Q11 QUOTA 20M ON CODEX_Q11");
        execute("CREATE USER "+reader+" IDENTIFIED BY "+password);
        execute("GRANT CREATE SESSION TO "+reader);
        dto.setUsername(reader);dto.setPassword(password);
        route=resolver.resolve(4L,QUALITY_CHECK_OBJECT_NOT_EXISTS);assertEquals("fixture-v1",route.getConfigurationRevision());
    }
    @AfterEach void cleanup() throws Exception {
        try {if(admin!=null){
            execute("DROP USER IF EXISTS "+reader+" CASCADE");execute("DROP USER IF EXISTS "+schema+" CASCADE");
        }} finally {try{if(admin!=null)admin.close();}finally{if(pool!=null)pool.close();}}
    }

    @Test void actualSelectOnlyTaskKeepsHeapIdentityAndNotNullCanRecheck() throws Exception {
        create("NUMBER(19,0)");execute("INSERT INTO "+table()+" VALUES(9007199254740992,1,9,NULL),(9007199254740993,2,10,0)");
        var pools=new ArrayList<HikariDataSource>();var tracked=spy(resolver);
        doAnswer(call->{var created=resolver.createDataSource(call.getArgument(0));pools.add(created);return created;}).when(tracked).createDataSource(any());
        var executor=QualityRuntimeFixture.executor();ReflectionTestUtils.setField(executor,"databaseResolver",tracked);
        ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());ReflectionTestUtils.setField(executor,"auditLogMapper",mock(QualityAuditLogMapper.class));
        var originals=new HashMap<Boolean,Run>();var repaired=new HashMap<Boolean,Run>();String fingerprint=null;
        for(boolean fixed:List.of(false,true)){
            if(fixed)execute("UPDATE "+table()+" SET amount=0");
            for(boolean composite:List.of(false,true)){
                var result=run(executor,10L+pools.size(),composite);var audit=result.audit();
                var read=JsonUtils.parseObject(audit.getReadEvidence(),QualityReadEvidence.class);
                assertEquals(fixed?"PASS":"FAIL",audit.getResultType(),audit.getErrorDetail());
                assertEquals(2L,audit.getTotalCount());assertEquals(fixed?0L:1L,audit.getFailCount());
                assertEquals("FULL_PREDICATE",read.getCoverage());
                assertTrue(read.getRelationFingerprint().matches("[0-9a-f]{64}"));
                assertEquals(composite?QualityReadEvidence.COMPOSITE_SCHEMA_VERSION:QualityReadEvidence.RELATION_SCHEMA_VERSION,read.getSchemaVersion());
                if(fingerprint==null)fingerprint=read.getRelationFingerprint();else assertEquals(fingerprint,read.getRelationFingerprint());
                assertTrue(read.getKeyScope().getComplete(),read.getKeyScope().getReasonCode());
                assertEquals(composite?AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM:AuditKeySnapshot.ALGORITHM,read.getKeyScope().getAlgorithm());
                assertTrue(read.getOrgScope().getComplete(),read.getOrgScope().getReasonCode());
                assertEquals(AuditKeySnapshot.ORACLE_INTEGER_GROUP_ENCODING,read.getOrgScope().getGroupEncoding());
                assertTrue(pools.stream().allMatch(HikariDataSource::isClosed));assertFalse(admin.isClosed());
                (fixed?repaired:originals).put(composite,result);
                if(fixed)for(String scope:List.of("ALL","ORG")){
                    var comparison=compare(originals.get(composite),result,scope);
                    assertTrue(comparison.comparable(),String.valueOf(comparison.evidence()));
                    assertEquals("PASS",comparison.resultType());
                }
            }
        }
        execute("TRUNCATE TABLE "+table());
        execute("INSERT INTO "+table()+" VALUES(9007199254740992,1,9,0),(9007199254740993,2,10,0)");
        var rebuilt=run(executor,80,false);assertEquals("PASS",rebuilt.audit().getResultType());
        for(String scope:List.of("ALL","ORG")){
            var changed=compare(originals.get(false),rebuilt,scope);
            assertFalse(changed.comparable());assertEquals("RELATION_CHANGED",changed.evidence().get("reasonCode"));
        }
        execute("CREATE OR REPLACE FUNCTION "+schema+".ALLOW_ALL(object_schema VARCHAR2, object_name VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN '1=1'; END;");
        execute("BEGIN DBMS_RLS.ADD_POLICY('"+schema+"','SAMPLE','Q11_POL','"+schema+"','ALLOW_ALL','SELECT'); END;");
        try {
            var hidden=run(executor,90,false);
            var hiddenEvidence=JsonUtils.parseObject(hidden.audit().getReadEvidence(),QualityReadEvidence.class);
            assertEquals("RELATION_UNPROVEN",hiddenEvidence.getCoverage());assertNull(hiddenEvidence.getRelationFingerprint());
            assertFalse(compare(originals.get(false),hidden,"ALL").comparable());
        } finally {execute("BEGIN DBMS_RLS.DROP_POLICY('"+schema+"','SAMPLE','Q11_POL'); END;");}
        for(var sample:List.of(originals.get(false),repaired.get(false))){
            var definition=JsonUtils.parseObject(sample.instance().getDefinitionSnapshot(),QualityTaskExecutor.GateDefinition.class);
            definition.getRules().getFirst().setStrategyKey("LENGTH_VALIDATION");
            sample.instance().setDefinitionSnapshot(JsonUtils.toJsonString(definition));
        }
        var unverified=compare(originals.get(false),repaired.get(false),"ALL");
        assertFalse(unverified.comparable());assertEquals("RULE_DIALECT_UNVERIFIED",unverified.evidence().get("reasonCode"));
        try(var readPool=resolver.createDataSource(route);var connection=readPool.getConnection();var sql=connection.createStatement()){
            assertThrows(SQLException.class,()->sql.execute("UPDATE "+table()+" SET amount=1"));
        }
    }

    @ParameterizedTest @ValueSource(ints={2,3,4})
    void compositeColumnOrderChangesTheTupleHash(int size)throws Exception{
        create("NUMBER(19,0)");
        execute("ALTER TABLE "+table()+" ADD (THIRD_KEY NUMBER(19,0), FOURTH_KEY NUMBER(19,0))");
        execute("INSERT INTO "+table()+" VALUES(-9223372036854775808,11,9007199254740992,NULL,21,31),(9223372036854775807,12,9007199254740993,0,22,32)");
        var columns=List.of("ID","PART","THIRD_KEY","FOURTH_KEY").subList(0,size);
        try(var readPool=resolver.createDataSource(route);var connection=readPool.getConnection()){
            var snapshot=AuditKeySnapshot.readComposite(connection,generator,context(),columns,10,10);
            assertTrue(snapshot.complete(),snapshot.reasonCode());
            assertEquals(AuditKeySnapshot.ORACLE_COMPOSITE_ALGORITHM,snapshot.algorithm());
            assertEquals(snapshot,JsonUtils.parseObject(JsonUtils.toJsonString(snapshot),AuditKeySnapshot.class));
            var reversed=new ArrayList<>(columns);Collections.reverse(reversed);
            assertNotEquals(snapshot.keyHash(),AuditKeySnapshot.readComposite(connection,generator,context(),reversed,10,10).keyHash());
            var groups=AuditKeySnapshot.readGroupedComposite(connection,generator,context(),columns,"ORG_CODE",10,10,10);
            assertTrue(groups.complete(),groups.reasonCode());
            assertEquals(AuditKeySnapshot.ORACLE_INTEGER_GROUP_ENCODING,groups.groupEncoding());
            assertEquals(Set.of("9007199254740992","9007199254740993"),new HashSet<>(groups.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
            assertFalse(connection.isClosed());assertTrue(connection.getAutoCommit());
        }
    }

    @Test void replacedKeysPartSwapAndOrgMoveAreDifferentPopulations()throws Exception{
        create("NUMBER(19,0)");
        execute("INSERT INTO "+table()+" VALUES(9007199254740992,11,9,NULL),(9007199254740993,12,9,0),(9007199254740994,13,10,0)");
        var executor=QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor,"databaseResolver",resolver);
        ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());
        ReflectionTestUtils.setField(executor,"auditLogMapper",mock(QualityAuditLogMapper.class));
        var before=run(executor,10,true);
        execute("UPDATE "+table()+" SET amount=0 WHERE amount IS NULL");
        var repaired=run(executor,11,true);
        for(String scope:List.of("ALL","ORG")){
            var accepted=compare(before,repaired,scope);
            assertTrue(accepted.comparable(),String.valueOf(accepted.evidence()));
            assertEquals("PASS",accepted.resultType());
        }
        execute("UPDATE "+table()+" SET id=9007199254740995 WHERE id=9007199254740992");
        assertPopulationChanged(compare(before,run(executor,12,true),"ALL"));
        assertPopulationChanged(compare(before,run(executor,13,true),"ORG"));
        execute("UPDATE "+table()+" SET id=9007199254740992 WHERE id=9007199254740995");
        execute("UPDATE "+table()+" SET part=CASE WHEN id=9007199254740992 THEN 12 ELSE 11 END WHERE id IN (9007199254740992,9007199254740993)");
        assertPopulationChanged(compare(before,run(executor,14,true),"ALL"));
        execute("UPDATE "+table()+" SET part=CASE WHEN id=9007199254740992 THEN 11 ELSE 12 END WHERE id IN (9007199254740992,9007199254740993)");
        execute("UPDATE "+table()+" SET org_code=10 WHERE id=9007199254740992");
        execute("UPDATE "+table()+" SET org_code=9 WHERE id=9007199254740994");
        assertPopulationChanged(compare(before,run(executor,15,true),"ORG"));
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"NUMBER(1,0)|-9|9","NUMBER(38,0)|9007199254740992|9007199254740993","NUMBER(19,0)|-9223372036854775808|9223372036854775807"})
    void integerSingleTupleAndInstitutionScansRespectTypesAndGlobalLimits(String type,String low,String high)throws Exception{
        create(type);execute("INSERT INTO "+table()+" VALUES("+low+",11,"+low+",NULL),("+high+",12,"+high+",0)");
        try(var readPool=resolver.createDataSource(route);var connection=readPool.getConnection()){
            var single=single(connection);var tuple=tuple(connection);assertTrue(single.complete(),single.reasonCode());assertTrue(tuple.complete(),tuple.reasonCode());
            assertEquals(2,single.total());assertEquals(1,single.fail());assertEquals(tuple,JsonUtils.parseObject(JsonUtils.toJsonString(tuple),AuditKeySnapshot.class));
            assertTrue(single.sql().contains(" FETCH FIRST "));assertFalse(single.sql().contains(" LIMIT "));
            for(boolean composite:new boolean[]{false,true}){
                var groups=groups(connection,composite,2,2);assertTrue(groups.complete(),groups.reasonCode());
                assertEquals(Set.of(low,high),new HashSet<>(groups.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
                assertEquals("KEY_LIMIT_EXCEEDED",groups(connection,composite,1,2).reasonCode());
                assertEquals("GROUP_LIMIT_EXCEEDED",groups(connection,composite,2,1).reasonCode());
            }
            execute("UPDATE "+table()+" SET part=CASE WHEN part=11 THEN 12 ELSE 11 END");
            assertNotEquals(tuple.keyHash(),tuple(connection).keyHash());assertEquals(single.keyHash(),single(connection).keyHash());
            assertFalse(connection.isClosed());assertTrue(connection.getAutoCommit());
        }
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"NUMBER|1.5","NUMBER(19,2)|9.25","NUMBER(19,-2)|100","VARCHAR2(64)|'9'","FLOAT|9","BINARY_DOUBLE|9"})
    void unsupportedKeysDoNotAcquireIntegerEvidence(String type,String value)throws Exception{
        create(type);execute("INSERT INTO "+table()+" VALUES("+value+",1,"+value+",NULL)");
        assertEquals("KEY_TYPE_UNSUPPORTED",single(admin).reasonCode());assertEquals("KEY_TYPE_UNSUPPORTED",tuple(admin).reasonCode());
        for(boolean composite:new boolean[]{false,true})assertFalse(groups(admin,composite,10,10).complete());
    }

    @Test void nullDuplicateAndInstitutionKeysNeverProducePartialProof()throws Exception{
        create("NUMBER(19,0)");execute("INSERT INTO "+table()+" VALUES(1,1,9,NULL),(1,1,9,0)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL",single(admin).reasonCode());assertFalse(tuple(admin).complete());
        execute("UPDATE "+table()+" SET id=NULL");assertFalse(single(admin).complete());assertFalse(tuple(admin).complete());
        execute("UPDATE "+table()+" SET org_code=NULL");assertFalse(groups(admin,false,10,10).complete());assertFalse(groups(admin,true,10,10).complete());
    }

    @Test void nativeEmptyStringClobWhitespaceAndZeroSemantics()throws Exception{
        create("NUMBER(19,0)");execute("ALTER TABLE "+table()+" MODIFY AMOUNT VARCHAR2(100)");
        execute("INSERT INTO "+table()+" VALUES(1,1,9,NULL),(2,2,9,''),(3,3,9,' '),(4,4,9,'0'),(5,5,9,'中文')");
        assertEquals(2,single(admin).fail());var context=context();context.setConfig(Map.of("allowBlank",true));
        assertEquals(2,AuditKeySnapshot.read(admin,generator,context,"ID",10,10).fail());
        try(var query=admin.createStatement();var rows=query.executeQuery(generator.buildErrorDataSql(context(),1))){assertTrue(rows.next());assertFalse(rows.next());}
        execute("DROP TABLE "+table());create("NUMBER(19,0)");
        execute("ALTER TABLE "+table()+" DROP COLUMN AMOUNT");
        execute("ALTER TABLE "+table()+" ADD AMOUNT CLOB");
        execute("INSERT INTO "+table()+" VALUES(1,1,9,EMPTY_CLOB()),(2,2,9,' ')");
        assertEquals(1,single(admin).fail());
        assertEquals(0,AuditKeySnapshot.read(admin,generator,context,"ID",10,10).fail());
    }

    @Test void overflowDoesNotBecomeAnInt64Key()throws Exception{
        create("NUMBER(38,0)");execute("INSERT INTO "+table()+" VALUES(9223372036854775808,1,9,0)");
        assertFalse(single(admin).complete());assertFalse(tuple(admin).complete());
        execute("UPDATE "+table()+" SET ID=1,ORG_CODE=9223372036854775808");
        assertFalse(groups(admin,false,10,10).complete());assertFalse(groups(admin,true,10,10).complete());
    }

    private static void assertPopulationChanged(QualityRecheckEvidence.Comparison comparison){
        assertFalse(comparison.comparable(),String.valueOf(comparison.evidence()));
        assertEquals("ROW_POPULATION_CHANGED",comparison.evidence().get("reasonCode"));
    }
    private record Run(QualityTaskInstanceDO instance,QualityAuditLogDO audit){}
    private Run run(QualityTaskExecutor executor,long id,boolean composite){
        var task=QualityTaskDO.builder().id(1L).orgField("ORG_CODE").build();
        var object=QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();
        var rule=QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(composite?"{\"column\":\"AMOUNT\",\"pkColumns\":[\"ID\",\"PART\"]}":"{\"column\":\"AMOUNT\",\"pkColumn\":\"ID\"}").collectIssueDetail(0).build();
        var definition=new QualityTaskExecutor.GateDefinition();definition.setTask(task);definition.setObjects(List.of(object));definition.setRules(List.of(rule));
        var instance=QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS").definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        List<QualityAuditLogDO> logs=new ArrayList<>();QualityRuntimeFixture.executeObject(executor,id,task,route,object,List.of(rule),logs);
        assertEquals(1,logs.size());var audit=logs.getFirst();audit.setId(id*10);instance.setEndTime(LocalDateTime.now());return new Run(instance,audit);
    }
    private QualityRecheckEvidence.Comparison compare(Run before,Run after,String scope){
        var issue=QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L).instanceId(before.instance().getId()).auditLogId(before.audit().getId())
                .tableName(table()).checkField("AMOUNT").statScope(scope).regionCode("ORG".equals(scope)?"9":null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(),issue,before.instance(),before.audit(),after.instance(),after.audit(),before.instance().getEndTime());
    }
    private AuditRuleContext context(){var context=new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());context.setProductType(ProductTypeEnum.ORACLE);context.setTableName(table());context.setColumn("AMOUNT");context.setConfig(Map.of());return context;}
    private AuditKeySnapshot single(Connection c)throws SQLException{return AuditKeySnapshot.read(c,generator,context(),"ID",10,10);}
    private AuditKeySnapshot tuple(Connection c)throws SQLException{return AuditKeySnapshot.readComposite(c,generator,context(),List.of("ID","PART"),10,10);}
    private AuditKeySnapshot.Grouped groups(Connection c,boolean composite,int keys,int groups)throws SQLException{return composite?AuditKeySnapshot.readGroupedComposite(c,generator,context(),List.of("ID","PART"),"ORG_CODE",keys,groups,10):AuditKeySnapshot.readGrouped(c,generator,context(),"ID","ORG_CODE",keys,groups,10);}
    private void create(String type)throws SQLException{execute("CREATE TABLE "+table()+"(ID "+type+",PART NUMBER(19,0),ORG_CODE "+type+",AMOUNT NUMBER(19,0))");execute("GRANT SELECT ON "+table()+" TO "+reader);}
    private String table(){return schema+".SAMPLE";}
    private void execute(String text)throws SQLException{
        try(var sql=admin.createStatement()){sql.setQueryTimeout(15);sql.execute(text);}
        catch(SQLException failure){if(text.startsWith("CREATE USER "))throw new SQLException("Fixture user creation failed",failure.getSQLState(),failure.getErrorCode());throw failure;}
    }
}
