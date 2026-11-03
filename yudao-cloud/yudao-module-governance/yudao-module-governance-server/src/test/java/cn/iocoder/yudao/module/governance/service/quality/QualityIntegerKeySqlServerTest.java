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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native scanning and task entry through deployment-pinned optional SQL Server driver. */
@EnabledIfSystemProperty(named="datahub.test.sqlserver.url",matches="jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
@EnabledIfSystemProperty(named="datahub.test.sqlserver.driver",matches=".+")
class QualityIntegerKeySqlServerTest {
    private HikariDataSource pool;
    private Connection admin;
    private MasterDatabaseResolver resolver;
    private MasterDatabaseResolver.ConnectionInfo route;
    private String schema, reader, password;
    private final AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");schema="q27_"+suffix;reader="qr27_"+suffix;password="Q!"+UUID.randomUUID()+"xY8";
        Path driver=Path.of(System.getProperty("datahub.test.sqlserver.driver")).toRealPath();
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(driver)));
        var dto=new DatabaseConnectionRespDTO();dto.setId(4L);dto.setProductType("SQLSERVER");dto.setSchema(schema);dto.setUsageType(1);
        dto.setJdbcUrl(System.getProperty("datahub.test.sqlserver.url"));dto.setUsername("sa");dto.setPassword(System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD"));dto.setConfigurationRevision("fixture-v1");
        var api=mock(DatabaseApi.class);when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class,()->api).withBean(MasterDatabaseResolver.class)
                .withPropertyValues("yudao.dbswitch.drivers.SQLSERVER.path="+driver,"yudao.dbswitch.drivers.SQLSERVER.sha256="+sha)
                .run(context->{assertNull(context.getStartupFailure());resolver=context.getBean(MasterDatabaseResolver.class);
                    pool=resolver.createDataSource(resolver.resolve(4L,QUALITY_CHECK_OBJECT_NOT_EXISTS));admin=pool.getConnection();});
        execute("CREATE SCHEMA "+schema);
        execute("CREATE LOGIN "+reader+" WITH PASSWORD='"+password+"', CHECK_POLICY=OFF");
        execute("CREATE USER "+reader+" FOR LOGIN "+reader);execute("GRANT SELECT ON SCHEMA::"+schema+" TO "+reader);
        dto.setUsername(reader);dto.setPassword(password);
        route=resolver.resolve(4L,QUALITY_CHECK_OBJECT_NOT_EXISTS);assertTrue(route.getConfigurationRevision().startsWith("jdbc-v1:"));
    }
    @AfterEach void cleanup() throws Exception {
        try {if(admin!=null){
            execute("IF OBJECT_ID(N'"+table()+"',N'U') IS NOT NULL DROP TABLE "+table());
            execute("DROP USER "+reader);execute("DROP LOGIN "+reader);execute("DROP SCHEMA "+schema);
        }} finally {try{if(admin!=null)admin.close();}finally{if(pool!=null)pool.close();}}
    }

    @Test void actualSelectOnlyTaskReadsNewCountsButCannotPromoteMissingRelationIdentity() throws Exception {
        create("BIGINT");execute("INSERT INTO "+table()+" VALUES(9007199254740992,1,9,NULL),(9007199254740993,2,10,0)");
        var pools=new ArrayList<HikariDataSource>();var tracked=spy(resolver);
        doAnswer(call->{var created=resolver.createDataSource(call.getArgument(0));pools.add(created);return created;}).when(tracked).createDataSource(any());
        var executor=QualityRuntimeFixture.executor();ReflectionTestUtils.setField(executor,"databaseResolver",tracked);
        ReflectionTestUtils.setField(executor,"auditSqlRegistry",new AuditSqlRegistry());ReflectionTestUtils.setField(executor,"auditLogMapper",mock(QualityAuditLogMapper.class));
        var originals=new HashMap<Boolean,Run>();
        for(boolean repaired:List.of(false,true)){
            if(repaired)execute("UPDATE "+table()+" SET amount=0");
            for(boolean composite:List.of(false,true)){
                var result=run(executor,10L+pools.size(),composite);var audit=result.audit();
                var read=JsonUtils.parseObject(audit.getReadEvidence(),QualityReadEvidence.class);
                assertEquals(repaired?"PASS":"FAIL",audit.getResultType(),audit.getErrorDetail());
                assertEquals(2L,audit.getTotalCount());assertEquals(repaired?0L:1L,audit.getFailCount());
                assertEquals("RELATION_UNPROVEN",read.getCoverage());assertNull(read.getRelationFingerprint());
                assertTrue(read.getKeyScope().getComplete(),read.getKeyScope().getReasonCode());
                assertEquals(composite?AuditKeySnapshot.SQLSERVER_COMPOSITE_ALGORITHM:AuditKeySnapshot.ALGORITHM,read.getKeyScope().getAlgorithm());
                assertTrue(read.getOrgScope().getComplete(),read.getOrgScope().getReasonCode());
                assertEquals(AuditKeySnapshot.SQLSERVER_INTEGER_GROUP_ENCODING,read.getOrgScope().getGroupEncoding());
                assertTrue(pools.stream().allMatch(HikariDataSource::isClosed));assertFalse(admin.isClosed());
                if(!repaired)originals.put(composite,result);
                else for(String scope:List.of("ALL","ORG")){
                    var before=originals.get(composite);var comparison=compare(before,result,scope);
                    assertFalse(comparison.comparable());assertEquals("READ_EVIDENCE_MISSING",comparison.evidence().get("reasonCode"));
                }
            }
        }
        try(var readPool=resolver.createDataSource(route);var connection=readPool.getConnection();var sql=connection.createStatement()){
            assertThrows(SQLException.class,()->sql.execute("UPDATE "+table()+" SET amount=1"));
        }
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"SMALLINT|-32768|32767","INT|-2147483648|2147483647","BIGINT|-9223372036854775808|9223372036854775807"})
    void integerSingleTupleAndInstitutionScansRespectTypesAndGlobalLimits(String type,String low,String high)throws Exception{
        create(type);execute("INSERT INTO "+table()+" VALUES("+low+",11,"+low+",NULL),("+high+",12,"+high+",0)");
        try(var readPool=resolver.createDataSource(route);var connection=readPool.getConnection()){
            var single=single(connection);var tuple=tuple(connection);assertTrue(single.complete(),single.reasonCode());assertTrue(tuple.complete(),tuple.reasonCode());
            assertEquals(2,single.total());assertEquals(1,single.fail());assertEquals(tuple,JsonUtils.parseObject(JsonUtils.toJsonString(tuple),AuditKeySnapshot.class));
            assertTrue(single.sql().startsWith("SELECT TOP ("));assertFalse(single.sql().contains(" LIMIT "));
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

    @ParameterizedTest @CsvSource(delimiter='|',value={"TINYINT|9","DECIMAL(20,0)|9","VARCHAR(64)|'9'","NVARCHAR(64)|N'9'","BIT|1","FLOAT|9","VARBINARY(32)|0x39"})
    void unsupportedKeysDoNotAcquireIntegerEvidence(String type,String value)throws Exception{
        create(type);execute("INSERT INTO "+table()+" VALUES("+value+",1,"+value+",NULL)");
        assertEquals("KEY_TYPE_UNSUPPORTED",single(admin).reasonCode());assertEquals("KEY_TYPE_UNSUPPORTED",tuple(admin).reasonCode());
        for(boolean composite:new boolean[]{false,true})assertFalse(groups(admin,composite,10,10).complete());
    }

    @Test void nullDuplicateAndInstitutionKeysNeverProducePartialProof()throws Exception{
        create("BIGINT");execute("INSERT INTO "+table()+" VALUES(1,1,9,NULL),(1,1,9,0)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL",single(admin).reasonCode());assertFalse(tuple(admin).complete());
        execute("UPDATE "+table()+" SET id=NULL");assertFalse(single(admin).complete());assertFalse(tuple(admin).complete());
        execute("UPDATE "+table()+" SET org_code=NULL");assertFalse(groups(admin,false,10,10).complete());assertFalse(groups(admin,true,10,10).complete());
    }

    @Test void nullEmptyWhitespaceZeroAndQuotedIdentifierModeHaveNativeSemantics()throws Exception{
        create("BIGINT");execute("ALTER TABLE "+table()+" ALTER COLUMN amount NVARCHAR(100)");
        execute("INSERT INTO "+table()+" VALUES(1,1,9,NULL),(2,2,9,N''),(3,3,9,N' '),(4,4,9,N'0'),(5,5,9,N'中文')");
        execute("SET QUOTED_IDENTIFIER OFF");
        try{
            assertEquals(2,single(admin).fail());var context=context();context.setConfig(Map.of("allowBlank",true));
            assertEquals(1,AuditKeySnapshot.read(admin,generator,context,"id",10,10).fail());
            try(var query=admin.createStatement();var rows=query.executeQuery(generator.buildErrorDataSql(context(),1))){assertTrue(rows.next());assertFalse(rows.next());}
        }finally{execute("SET QUOTED_IDENTIFIER ON");}
    }

    private record Run(QualityTaskInstanceDO instance,QualityAuditLogDO audit){}
    private Run run(QualityTaskExecutor executor,long id,boolean composite){
        var task=QualityTaskDO.builder().id(1L).orgField("org_code").build();
        var object=QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY").tableName(table()).build();
        var rule=QualityRuleDO.builder().id(2L).status(0).strategyKey("NOT_NULL_VALIDATION").checkLevel("FIELD")
                .paramSchema(composite?"{\"column\":\"amount\",\"pkColumns\":[\"id\",\"part\"]}":"{\"column\":\"amount\",\"pkColumn\":\"id\"}").collectIssueDetail(0).build();
        var definition=new QualityTaskExecutor.GateDefinition();definition.setTask(task);definition.setObjects(List.of(object));definition.setRules(List.of(rule));
        var instance=QualityTaskInstanceDO.builder().id(id).taskId(1L).instanceStatus("SUCCESS").definitionSnapshot(JsonUtils.toJsonString(definition)).startTime(LocalDateTime.now()).build();
        List<QualityAuditLogDO> logs=new ArrayList<>();QualityRuntimeFixture.executeObject(executor,id,task,route,object,List.of(rule),logs);
        assertEquals(1,logs.size());var audit=logs.getFirst();audit.setId(id*10);instance.setEndTime(LocalDateTime.now());return new Run(instance,audit);
    }
    private QualityRecheckEvidence.Comparison compare(Run before,Run after,String scope){
        var issue=QualityIssueDataDO.builder().id(30L).taskId(1L).ruleId(2L).objectId(3L).instanceId(before.instance().getId()).auditLogId(before.audit().getId())
                .tableName(table()).checkField("amount").statScope(scope).regionCode("ORG".equals(scope)?"9":null).issueCount(1L).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(1L).build(),issue,before.instance(),before.audit(),after.instance(),after.audit(),before.instance().getEndTime());
    }
    private AuditRuleContext context(){var context=new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());context.setProductType(ProductTypeEnum.SQLSERVER);context.setTableName(table());context.setColumn("amount");context.setConfig(Map.of());return context;}
    private AuditKeySnapshot single(Connection c)throws SQLException{return AuditKeySnapshot.read(c,generator,context(),"id",10,10);}
    private AuditKeySnapshot tuple(Connection c)throws SQLException{return AuditKeySnapshot.readComposite(c,generator,context(),List.of("id","part"),10,10);}
    private AuditKeySnapshot.Grouped groups(Connection c,boolean composite,int keys,int groups)throws SQLException{return composite?AuditKeySnapshot.readGroupedComposite(c,generator,context(),List.of("id","part"),"org_code",keys,groups,10):AuditKeySnapshot.readGrouped(c,generator,context(),"id","org_code",keys,groups,10);}
    private void create(String type)throws SQLException{execute("CREATE TABLE "+table()+"(id "+type+",part BIGINT,org_code "+type+",amount BIGINT)");}
    private String table(){return schema+".sample";}
    private void execute(String text)throws SQLException{try(var sql=admin.createStatement()){sql.setQueryTimeout(15);sql.execute(text);}}
}
