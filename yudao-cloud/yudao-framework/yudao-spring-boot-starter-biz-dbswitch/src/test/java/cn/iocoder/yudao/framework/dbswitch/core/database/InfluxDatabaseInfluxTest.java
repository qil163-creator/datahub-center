package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="datahub.test.influx.http",matches="http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named="DATAHUB_INFLUX_TEST_TOKEN",matches="apiv3_.+")
class InfluxDatabaseInfluxTest {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String schema, url;
    private Connection connection;
    private IDatabaseInterface database;
    @BeforeAll void setup() throws Exception {
        schema="q11inf_"+UUID.randomUUID().toString().replace("-", "");
        request("POST","/api/v3/configure/database","{\"db\":\""+schema+"\"}","application/json");
        request("POST","/api/v3/write_lp?db="+schema+"&precision=millisecond",
                "sample,site=north id=9007199254740993i,amount=1i,org_code=9i "+System.currentTimeMillis(),"text/plain");
        request("POST","/api/v3/write_lp?db="+schema+"&precision=millisecond",
                "native_types signed=-9223372036854775808i,unsigned=18446744073709551615u,approx=1.25,text=\"9007199254740993\",flag=true "+System.currentTimeMillis(),"text/plain");
        URI endpoint=URI.create(System.getProperty("datahub.test.influx.http"));
        url="jdbc:arrow-flight-sql://127.0.0.1:"+endpoint.getPort()+"/?useEncryption=0&database="+schema;
        var props=new Properties();props.setProperty("token",System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));
        Class.forName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        connection=DriverManager.getConnection(url,props);
        database=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.INFLUXDB3);
    }
    @AfterAll void cleanup() throws Exception {
        try {if(connection!=null)connection.close();}
        finally {try {if(schema!=null)request("DELETE","/api/v3/configure/database?db="+schema,"","application/json");} finally {http.close();}}
    }
    private void request(String method,String path,String body,String contentType) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http")+path))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+System.getenv("DATAHUB_INFLUX_TEST_TOKEN"))
                .header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
        assertTrue(status>=200&&status<300,()->"Influx fixture HTTP status "+status);
    }
    @Test void nativeJdbcReadsLargeSignedIntegerWithoutLoss() throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,org_code FROM iox.sample")) {
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong("id"));assertEquals(9L,rows.getLong("org_code"));assertFalse(rows.next());
        }
    }
    @Test void componentMetadataUsesPhysicalIoxSchema() {
        assertTrue(database.querySchemaList(connection).contains("iox"));
        assertTrue(database.queryTableList(connection,"iox").stream().anyMatch(t->"sample".equals(t.getTableName())));
        assertTrue(database.queryTableColumnMeta(connection,"iox","sample").stream().anyMatch(c->"id".equals(c.getFieldName())));
    }
    @Test void configuredPoolPassesTokenAsSecretPropertyNotUrl() throws Exception {
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        properties.setUrl(url);properties.setPassword(System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));properties.setPoolSize(1);properties.setConnectionTimeout(2000L);
        try(var pool=DataSourceUtils.createTargetDataSource(properties);var read=pool.getConnection();var statement=read.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM iox.sample")) {
            assertTrue(rows.next());assertEquals(1,rows.getLong(1));
            assertFalse(pool.getJdbcUrl().contains(System.getenv("DATAHUB_INFLUX_TEST_TOKEN")));
            assertNull(pool.getUsername());assertFalse(pool.getDataSourceProperties().containsKey("token"));
        }
    }

    @Test void pinnedDriverPoolUsesTheSameBearerContract() throws Exception {
        var jar=java.nio.file.Path.of(Class.forName(ProductTypeEnum.INFLUXDB3.getDriveClassName()).getProtectionDomain().getCodeSource().getLocation().toURI());
        var config=new DbSwitchProperties.Driver();config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(jar))));
        var driver=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,config)).find(ProductTypeEnum.INFLUXDB3).orElseThrow();
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        properties.setUrl(url);properties.setPassword(System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));properties.setUsername("ignored_for_bearer");
        properties.setPoolSize(1);properties.setConnectionTimeout(5000L);
        try(var pool=DataSourceUtils.createTargetDataSource(properties,null,null,driver);var read=pool.getConnection();
            var statement=read.createStatement();var rows=statement.executeQuery("SELECT id FROM iox.sample")) {
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));
            assertNull(pool.getUsername());assertFalse(pool.getDataSourceProperties().containsKey("token"));
        }
    }

    @Test void sourcePoolSupportsExistingCredentialFieldWithoutBasicAuth() throws Exception {
        var properties=new SourceDataSourceProperties();properties.setSourceProductType(ProductTypeEnum.INFLUXDB3);
        properties.setUrl(url);properties.setPassword(System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));properties.setUsername("ignored_for_bearer");
        properties.setPoolSize(1);properties.setConnectionTimeout(5000L);
        try(var pool=DataSourceUtils.createSourceDataSource(properties);var read=pool.getConnection();
            var statement=read.createStatement();var rows=statement.executeQuery("SELECT id FROM iox.sample")) {
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));
            assertNull(pool.getUsername());assertTrue(pool.getDataSourceProperties().isEmpty());
        }
    }

    @Test void userCredentialFallbackRemainsSupported() throws Exception {
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        properties.setUrl(url);properties.setUsername(System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));properties.setPoolSize(1);properties.setConnectionTimeout(5000L);
        try(var pool=DataSourceUtils.createTargetDataSource(properties);var read=pool.getConnection();
            var statement=read.createStatement();var rows=statement.executeQuery("SELECT 1")) {assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
    }

    @Test void signedIntegerProjectionHasSameResultTypeWitness() throws Exception {
        var projection=database.integerKeyProjection("source","signed");
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT "+projection.valueSql()+" AS value, "+projection.typeSql()+" AS witness FROM iox.native_types source LIMIT 2")) {
            assertTrue(projection.requiresRowTypeWitness());assertEquals(0,projection.jdbcType(rows.getMetaData(),2));
            assertTrue(rows.next());assertEquals(Types.BIGINT,projection.jdbcType(rows,2));assertEquals("-9223372036854775808",rows.getString(1));assertFalse(rows.next());
        }
    }

    @Test void unsignedFloatingTextBooleanAndTimestampCannotBorrowSignedIntegerEncoding() throws Exception {
        for(String column:List.of("unsigned","approx","text","flag","time")) {
            var projection=database.integerKeyProjection("source",column);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT "+projection.valueSql()+" AS value, "+projection.typeSql()+" AS witness FROM iox.native_types source LIMIT 2")) {
                assertEquals(0,projection.jdbcType(rows.getMetaData(),2),column);assertTrue(rows.next());assertEquals(0,projection.jdbcType(rows,2),column);
            }
        }
    }

    @Test void integerComponentBudgetCannotTruncateIntoAnotherIdentity() throws Exception {
        var projection=database.keyComponentProjection("source","signed",3);
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT "+projection.valueSql()+" AS value, "+projection.typeSql()+" AS witness FROM iox.native_types source LIMIT 2")) {
            assertTrue(rows.next());assertEquals(Types.BIGINT,projection.jdbcType(rows,2));assertNull(rows.getString(1));
        }
    }
}
