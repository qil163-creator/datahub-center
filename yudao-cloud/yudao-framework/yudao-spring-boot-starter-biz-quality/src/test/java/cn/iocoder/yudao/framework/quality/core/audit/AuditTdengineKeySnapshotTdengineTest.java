package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.tdengine.url",matches="jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfEnvironmentVariable(named="DATAHUB_TDENGINE_TEST_PASSWORD",matches=".+")
@EnabledIfSystemProperty(named="datahub.test.tdengine.bundle-manifest",matches=".+")
class AuditTdengineKeySnapshotTdengineTest {
    private Connection connection;
    private com.zaxxer.hikari.HikariDataSource pool;
    private ConfiguredJdbcDrivers registry;
    private Statement sql;
    private String schema;
    private final AuditRuleContext context=new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
    private final AuditSqlGenerator generator=new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");

    @BeforeEach void setup() throws Exception {
        var manifest=java.nio.file.Path.of(System.getProperty("datahub.test.tdengine.bundle-manifest")).toRealPath();
        var driver=JsonUtils.parseObject(java.nio.file.Files.readString(manifest),DbSwitchProperties.Driver.class);
        registry=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.TDENGINE,driver));
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.TDENGINE);
        properties.setUrl(System.getProperty("datahub.test.tdengine.url"));properties.setUsername("root");
        properties.setPassword(System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD"));properties.setPoolSize(2);
        pool=DataSourceUtils.createTargetDataSource(properties,null,null,registry.find(ProductTypeEnum.TDENGINE).orElseThrow());
        connection=pool.getConnection();
        sql=connection.createStatement();sql.setQueryTimeout(10);
        schema="q11td_"+UUID.randomUUID().toString().replace("-","");
        sql.execute("CREATE DATABASE "+schema+" VGROUPS 1 BUFFER 8");
        sql.execute("CREATE TABLE "+schema+".sample(ts TIMESTAMP,id BIGINT,part INT,org_code INT,amount BIGINT)");
        context.setProductType(ProductTypeEnum.TDENGINE);context.setTableName(schema+".sample");context.setColumn("amount");context.setConfig(Map.of());
    }
    @AfterEach void cleanup() throws Exception {
        try {if(sql!=null&&schema!=null)sql.execute("DROP DATABASE IF EXISTS "+schema);}
        finally {
            try {if(sql!=null)sql.close();if(connection!=null)connection.close();}
            finally {try {if(pool!=null)pool.close();} finally {if(registry!=null)registry.close();}}
        }
    }
    private void insert(String rows) throws SQLException {sql.execute("INSERT INTO "+schema+".sample VALUES"+rows);}
    private AuditKeySnapshot read(boolean composite,int limit) throws SQLException {
        return composite ? AuditKeySnapshot.readComposite(connection,generator,context,List.of("id","part"),limit,10)
                : AuditKeySnapshot.read(connection,generator,context,"id",limit,10);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void largeIntegerKeysAndJsonRetainCompleteCountsAndStableOrder(boolean composite) throws Exception {
        insert("(now,-9223372036854775808,1,9,NULL)(now+1s,9007199254740992,2,9,0)(now+2s,9007199254740993,3,10,1)(now+3s,9223372036854775807,4,10,1)");
        var result=read(composite,4);
        assertTrue(result.complete(),result.reasonCode()); assertEquals(4,result.total());assertEquals(1,result.fail());
        assertEquals(composite ? "SHA256_SORTED_TDENGINE_INT_TUPLE_V1" : AuditKeySnapshot.ALGORITHM,result.algorithm());
        assertEquals(result,JsonUtils.parseObject(JsonUtils.toJsonString(result),AuditKeySnapshot.class));
        for(int i=0;i<10;i++)assertEquals(result.keyHash(),read(composite,4).keyHash());
        assertTrue(connection.getAutoCommit());assertFalse(connection.isClosed());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void rowAndInstitutionBudgetsNeverReturnPartialProof(boolean composite) throws Exception {
        insert("(now,1,1,9,NULL)(now+1s,2,2,10,1)");
        assertEquals("KEY_LIMIT_EXCEEDED",read(composite,1).reasonCode());
        var grouped=composite ? AuditKeySnapshot.readGroupedComposite(connection,generator,context,List.of("id","part"),"org_code",2,2,10)
                : AuditKeySnapshot.readGrouped(connection,generator,context,"id","org_code",2,2,10);
        assertTrue(grouped.complete(),grouped.reasonCode());assertEquals("TDENGINE_SIGNED_INT64_DECIMAL_V1",grouped.groupEncoding());
        assertEquals(Set.of("9","10"),new HashSet<>(grouped.groups().stream().map(AuditKeySnapshot.Group::key).toList()));
        assertTrue(grouped.groups().stream().allMatch(g->g.snapshot().complete()));
        var limited=composite ? AuditKeySnapshot.readGroupedComposite(connection,generator,context,List.of("id","part"),"org_code",2,1,10)
                : AuditKeySnapshot.readGrouped(connection,generator,context,"id","org_code",2,1,10);
        assertEquals("GROUP_LIMIT_EXCEEDED",limited.reasonCode());assertFalse(limited.complete());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void duplicateAndNullConfiguredKeysNeverQualifyEvenWithNativeTimestampPrimaryKey(boolean composite) throws Exception {
        insert("(now,1,1,9,NULL)(now+1s,1,1,9,1)");
        assertEquals("KEY_NOT_UNIQUE_OR_NULL",read(composite,10).reasonCode());
        insert("(now+2s,NULL,2,9,1)");
        assertFalse(read(composite,10).complete());
    }

    @Test void unverifiedNativeTimestampAndTextDoNotBorrowIntegerProof() throws Exception {
        insert("(now,1,1,9,NULL)");
        assertEquals("KEY_TYPE_UNSUPPORTED",AuditKeySnapshot.read(connection,generator,context,"ts",10,10).reasonCode());
        sql.execute("ALTER TABLE "+schema+".sample ADD COLUMN text_key VARCHAR(32)");
        assertEquals("KEY_TYPE_UNSUPPORTED",AuditKeySnapshot.read(connection,generator,context,"text_key",10,10).reasonCode());
    }
}
