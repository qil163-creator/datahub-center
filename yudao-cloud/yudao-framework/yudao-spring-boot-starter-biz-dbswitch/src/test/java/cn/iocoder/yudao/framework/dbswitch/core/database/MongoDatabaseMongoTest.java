package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.mongodb.url",matches="jdbc:mongodb://127[.]0[.]0[.]1:[0-9]+/admin\\?authSource=admin")
@EnabledIfSystemProperty(named="datahub.test.mongodb.driver",matches=".+")
@EnabledIfEnvironmentVariable(named="DATAHUB_MONGODB_TEST_PASSWORD",matches=".+")
class MongoDatabaseMongoTest {
    private HikariDataSource pool;
    private Connection connection;
    private String schema;
    private IDatabaseInterface database;
    private ConfiguredJdbcDrivers drivers;
    private final ClassLoader callerLoader = Thread.currentThread().getContextClassLoader();
    @BeforeEach void setup() throws Exception {
        var jar=Path.of(System.getProperty("datahub.test.mongodb.driver")).toRealPath();
        var config=new DbSwitchProperties.Driver();config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.MONGODB,config));
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.MONGODB);
        properties.setUrl(System.getProperty("datahub.test.mongodb.url"));properties.setUsername("q11root");
        properties.setPassword(System.getenv("DATAHUB_MONGODB_TEST_PASSWORD"));properties.setPoolSize(1);properties.setConnectionTimeout(10000L);
        pool=DataSourceUtils.createTargetDataSource(properties,null,null,drivers.find(ProductTypeEnum.MONGODB).orElseThrow());
        connection=pool.getConnection();
        assertNull(pool.getConnectionTestQuery(), "MongoDB pool validation must use native isValid, not shell/Graal");
        schema="q11mongo_"+UUID.randomUUID().toString().replace("-","");
        command(schema,"{create:'sample'}");
        command(schema,"{insert:'sample',documents:[{_id:{$numberLong:'9007199254740993'},amount:null,org_code:{$numberInt:'9'}}]}");
        database=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MONGODB);
    }
    @AfterEach void cleanup() throws Exception {
        try {if(connection!=null&&schema!=null)command(schema,"{dropDatabase:1}");}
        finally {if(connection!=null)connection.close();if(pool!=null)pool.close();if(drivers!=null)drivers.close();Thread.currentThread().setContextClassLoader(callerLoader);}
    }
    @Test void nativeReadMetadataAndDdlUseTheRequestedDatabase() throws Exception {
        assertTrue(database.querySchemaList(connection).contains(schema));
        assertTrue(database.queryTableList(connection,schema).stream().anyMatch(t->"sample".equals(t.getTableName())));
        assertTrue(database.queryTableColumnName(connection,schema,"sample").contains("amount"));
        assertEquals(List.of("_id"),database.queryTablePrimaryKeys(connection,schema,"sample"));
        assertTrue(database.getTableDDL(connection,schema,"sample").contains("createCollection"));
        var result=command(schema,"{find:'sample',limit:1}").path("cursor").path("firstBatch");
        assertEquals(1,result.size()); assertEquals(9007199254740993L,
                cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.integer(result.get(0).path("_id")));
        assertTrue(database.queryTableColumnMeta(connection,schema,"sample").stream().anyMatch(c->"amount".equals(c.getFieldName())));
        assertFalse(connection.isClosed());
    }
    @Test void collectionGenerationIsStableForWritesAndChangesAfterRecreation() throws Exception {
        String first=database.queryRelationFingerprint(connection,schema+".sample");
        command(schema,"{insert:'sample',documents:[{_id:{$numberLong:'9007199254740994'},amount:1}]}");
        assertEquals(first,database.queryRelationFingerprint(connection,schema+".sample"));
        command(schema,"{drop:'sample'}");command(schema,"{create:'sample'}");
        assertNotEquals(first,database.queryRelationFingerprint(connection,schema+".sample"));
    }

    @Test void healthCheckPreservesCatalogAndCanExecuteNativePing() throws Exception {
        connection.setCatalog(schema);
        assertTrue(connection.isValid(5)); assertEquals(1,cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.integer(command(schema,"{ping:1}").path("ok")));
        assertEquals(schema,connection.getCatalog());
        assertSame(callerLoader,Thread.currentThread().getContextClassLoader());
    }

    @Test void explicitMetadataDoesNotDependOnSessionCatalog() throws Exception {
        connection.setCatalog("admin");
        assertTrue(database.queryTableColumnMeta(connection,schema,"sample").stream().anyMatch(c->"amount".equals(c.getFieldName())));
        assertEquals("admin",connection.getCatalog());
        assertEquals(List.of("_id"),database.queryTablePrimaryKeys(connection,schema,"sample"));
        assertEquals(List.of("_id"),database.queryTablePrimaryKeys(connection,schema,"sample"));
    }

    @Test void absentCollectionCannotInventPrimaryKeyOrFingerprint() {
        assertThrows(RuntimeException.class,()->database.queryTablePrimaryKeys(connection,schema,"absent"));
        assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,schema+".absent"));
        assertThrows(RuntimeException.class,()->database.getTableDDL(connection,schema,"absent"));
    }

    @Test void emptyCollectionHasStablePhysicalGeneration() throws Exception {
        command(schema,"{create:'empty'}");
        String identity=database.queryRelationFingerprint(connection,schema+".empty");
        assertEquals(identity,database.queryRelationFingerprint(connection,schema+".empty"));
        assertEquals(List.of("_id"),database.queryTablePrimaryKeys(connection,schema,"empty"));
        assertEquals(List.of("_id"),database.queryTableColumnMeta(connection,schema,"empty").stream().map(c->c.getFieldName()).toList());
    }

    @Test void viewAndCappedCollectionsAreNotOrdinaryRelations() throws Exception {
        command(schema,"{create:'sample_view',viewOn:'sample',pipeline:[]}");
        command(schema,"{create:'sample_capped',capped:true,size:4096}");
        assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,schema+".sample_view"));
        assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,schema+".sample_capped"));
    }

    @Test void collectionOptionsChangeInvalidatesIdentityAndCannotBeSilentlyDroppedFromDdl() throws Exception {
        String original=database.queryRelationFingerprint(connection,schema+".sample");
        command(schema,"{collMod:'sample',validator:{amount:{$type:['int','long','null']}}}");
        assertNotEquals(original,database.queryRelationFingerprint(connection,schema+".sample"));
        assertThrows(RuntimeException.class,()->database.getTableDDL(connection,schema,"sample"));
    }

    @Test void simpleNativeDdlExportKeepsItsExplicitDatabaseAndCallerCatalog() throws Exception {
        String ddl=database.getTableDDL(connection,schema,"sample");
        command(schema,"{drop:'sample'}");
        connection.setCatalog("admin");
        assertEquals("client.getDatabase('"+schema+"').createCollection('sample');",ddl);
        command(schema,"{create:'sample'}");
        assertEquals(List.of("_id"),database.queryTablePrimaryKeys(connection,schema,"sample"));
        assertEquals("admin",connection.getCatalog());
    }

    @Test void additionalIndexesCannotBeSilentlyLostInDdl() throws Exception {
        command(schema,"{createIndexes:'sample',indexes:[{key:{amount:1},name:'amount_1'}]}");
        command(schema,"{createIndexes:'sample',indexes:[{key:{org_code:1},name:'org_code_1'}]}");
        assertThrows(RuntimeException.class,()->database.getTableDDL(connection,schema,"sample"));
        assertNotNull(database.queryRelationFingerprint(connection,schema+".sample"));
    }

    @Test void readOnlyPrincipalGenerationAndRevocationAreVerified() throws Exception {
        String reader="r_"+UUID.randomUUID().toString().replace("-", "");
        String password=UUID.randomUUID().toString().replace("-", "");
        var createUser=new java.util.LinkedHashMap<String,Object>();createUser.put("createUser",reader);createUser.put("pwd",password);
        createUser.put("roles",List.of(Map.of("role","read","db",schema)));
        command("admin",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(createUser));
        try {
            var props=new TargetDataSourceProperties(); props.setTargetProductType(ProductTypeEnum.MONGODB);
            props.setUrl(System.getProperty("datahub.test.mongodb.url"));props.setUsername(reader);props.setPassword(password);
            props.setPoolSize(1); props.setConnectionTimeout(10000L);
            try(var readerPool=DataSourceUtils.createTargetDataSource(props,null,null,drivers.find(ProductTypeEnum.MONGODB).orElseThrow());
                var read=readerPool.getConnection()) {
                String identity=database.queryRelationFingerprint(read,schema+".sample");
                assertEquals(identity,database.queryRelationFingerprint(read,schema+".sample"));
                assertNotEquals(database.queryRelationFingerprint(connection,schema+".sample"),identity);
                command("admin","{revokeRolesFromUser:'"+reader+"',roles:[{role:'read',db:'"+schema+"'}]}");
                assertThrows(SQLException.class,()->database.queryRelationFingerprint(read,schema+".sample"));
            }
        } finally {command("admin","{dropUser:'"+reader+"'}");}
    }
    private com.fasterxml.jackson.databind.JsonNode command(String databaseName,String command) throws Exception {
        var session=cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection,32L*1024*1024,5);
        return cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,session.command(databaseName,session.encode(command)));
    }
}
