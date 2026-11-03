package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="datahub.test.hive.url",matches="jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named="datahub.test.hive.driver",matches=".+")
class AuditHiveKeySnapshotHiveTest {
    private final AuditSqlGenerator generator=new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
    private final AuditRuleContext context=new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
    private String schema;
    private Connection connection;
    private com.zaxxer.hikari.HikariDataSource pool;
    @BeforeAll void setup() throws Exception {
        var jar=Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        var config=new DbSwitchProperties.Driver();config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        var driver=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE,config)).find(ProductTypeEnum.HIVE).orElseThrow();
        var props=new TargetDataSourceProperties();props.setTargetProductType(ProductTypeEnum.HIVE);
        props.setUrl(System.getProperty("datahub.test.hive.url"));props.setUsername("hive");props.setPassword("");
        props.setPoolSize(1);props.setConnectionTimeout(10000L);
        pool=DataSourceUtils.createTargetDataSource(props,null,null,driver);connection=pool.getConnection();
        schema="q11h_"+UUID.randomUUID().toString().replace("-","");
        execute("CREATE DATABASE "+schema);
        execute("CREATE EXTERNAL TABLE "+schema+".sample (kind STRING, id BIGINT, part INT, org_code SMALLINT, amount BIGINT) STORED AS TEXTFILE TBLPROPERTIES ('external.table.purge'='true')");
        execute("INSERT INTO "+schema+".sample VALUES "
            +"('ok',-9223372036854775808,1,9,NULL),('ok',9007199254740992,2,9,0),('ok',9007199254740993,3,10,1),('ok',9223372036854775807,4,10,1),"
            +"('dup',1,1,9,NULL),('dup',1,1,9,1),('nil',1,1,9,1),('nil',NULL,2,9,1),('ng',1,1,9,1),('ng',2,2,NULL,1)");
        context.setProductType(ProductTypeEnum.HIVE);context.setTableName(schema+".sample");context.setColumn("amount");context.setConfig(Map.of());
    }
    @BeforeEach void reset() {context.setWhereClause("kind='ok'");}
    @AfterAll void cleanup() throws Exception {
        try {if(connection!=null&&schema!=null)execute("DROP DATABASE "+schema+" CASCADE");}
        finally {try {if(connection!=null)connection.close();}finally {if(pool!=null)pool.close();}}
    }
    private void execute(String sql) throws SQLException {try(var s=connection.createStatement()){s.setQueryTimeout(120);s.execute(sql);}}
    private AuditKeySnapshot read(boolean composite,int limit) throws SQLException {
        return composite?AuditKeySnapshot.readComposite(connection,generator,context,List.of("id","part"),limit,60)
                :AuditKeySnapshot.read(connection,generator,context,"id",limit,60);
    }
    private AuditKeySnapshot.Grouped groups(boolean composite,int keys,int groupLimit) throws SQLException {
        return composite?AuditKeySnapshot.readGroupedComposite(connection,generator,context,List.of("id","part"),"org_code",keys,groupLimit,60)
                :AuditKeySnapshot.readGrouped(connection,generator,context,"id","org_code",keys,groupLimit,60);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactNativeIntegerCountsAndDigestAreStable(boolean composite) throws Exception {
        var result=read(composite,4);assertTrue(result.complete(),result.reasonCode());
        assertEquals(4,result.total());assertEquals(1,result.fail());
        assertEquals(composite?AuditKeySnapshot.HIVE_COMPOSITE_ALGORITHM:AuditKeySnapshot.ALGORITHM,result.algorithm());
        for(int i=0;i<2;i++)assertEquals(result.keyHash(),read(composite,4).keyHash());
        assertEquals(result,JsonUtils.parseObject(JsonUtils.toJsonString(result),AuditKeySnapshot.class));
        assertFalse(AuditKeySnapshot.supportsEncoding("HIVE",result.algorithm(),result.jdbcType()),"Native relation generation remains unqualified");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void institutionsAndGlobalBudgetsRemainIsolated(boolean composite) throws Exception {
        var result=groups(composite,4,2);assertTrue(result.complete(),result.reasonCode());
        assertEquals(AuditKeySnapshot.HIVE_INTEGER_GROUP_ENCODING,result.groupEncoding());
        assertEquals(Set.of("9","10"),new HashSet<>(result.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
        assertTrue(result.groups().stream().allMatch(g->g.snapshot().complete()));
        assertEquals(4,result.groups().stream().mapToLong(g->g.snapshot().total()).sum());
        assertEquals("GROUP_LIMIT_EXCEEDED",groups(composite,4,1).reasonCode());
        assertEquals("KEY_LIMIT_EXCEEDED",read(composite,3).reasonCode());
        assertEquals("KEY_LIMIT_EXCEEDED",groups(composite,3,2).reasonCode());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void duplicateAndNullKeysOrInstitutionsCannotYieldEvidence(boolean composite) throws Exception {
        context.setWhereClause("kind='dup'");assertEquals("KEY_NOT_UNIQUE_OR_NULL",read(composite,4).reasonCode());
        context.setWhereClause("kind='nil'");assertFalse(read(composite,4).complete());
        context.setWhereClause("kind='ng'");var result=groups(composite,4,2);
        assertFalse(result.complete());assertEquals("GROUP_KEY_UNSUPPORTED",result.reasonCode());assertTrue(result.groups().isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void emptyPopulationRemainsEmptyWithoutBorrowingOldRows(boolean composite) throws Exception {
        context.setWhereClause("1=0");var result=read(composite,4);assertEquals(0,result.total());assertEquals(0,result.fail());
        var grouped=groups(composite,4,2);assertTrue(grouped.complete());assertTrue(grouped.groups().isEmpty());
    }
    @Test void textKeyAndTextInstitutionCannotBorrowIntegerEvidence() throws Exception {
        assertEquals("KEY_TYPE_UNSUPPORTED",AuditKeySnapshot.read(connection,generator,context,"kind",10,60).reasonCode());
        assertEquals("GROUP_KEY_UNSUPPORTED",AuditKeySnapshot.readGrouped(connection,generator,context,"id","kind",10,10,60).reasonCode());
        assertFalse(AuditKeySnapshot.readComposite(connection,generator,context,List.of("id","kind"),10,60).complete());
    }
}
