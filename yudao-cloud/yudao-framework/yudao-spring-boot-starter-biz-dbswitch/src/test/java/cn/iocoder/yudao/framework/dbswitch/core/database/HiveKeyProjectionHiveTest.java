package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="datahub.test.hive.url",matches="jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named="datahub.test.hive.driver",matches=".+")
class HiveKeyProjectionHiveTest {
    private final IDatabaseInterface database=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIVE);
    private com.zaxxer.hikari.HikariDataSource pool;
    private Connection connection;
    @BeforeAll void connect() throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        var config=new DbSwitchProperties.Driver();config.setPath(jar.toString());
        config.setSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        var driver=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE,config)).find(ProductTypeEnum.HIVE).orElseThrow();
        var props=new TargetDataSourceProperties();props.setTargetProductType(ProductTypeEnum.HIVE);
        props.setUrl(System.getProperty("datahub.test.hive.url"));props.setUsername("hive");props.setPassword("");
        props.setPoolSize(1);props.setConnectionTimeout(10000L);
        pool=DataSourceUtils.createTargetDataSource(props,null,null,driver);connection=pool.getConnection();
        assertEquals("Apache Hive",connection.getMetaData().getDatabaseProductName());
        assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("4.1."));
    }
    @AfterAll void close() throws Exception {try {if(connection!=null)connection.close();}finally {if(pool!=null)pool.close();}}

    @ParameterizedTest @CsvSource({"SMALLINT,5,-32768","INT,4,-2147483648","BIGINT,-5,-9223372036854775808","BIGINT,-5,9007199254740993","BIGINT,-5,9223372036854775807"})
    void nativeIntegerMetadataAndPayloadAreExact(String nativeType,int jdbc,long value) throws Exception {
        var projection=database.integerKeyProjection("k","id");
        try(var s=connection.createStatement()) {
            s.setQueryTimeout(30);s.setMaxRows(2);
            try(var r=s.executeQuery("SELECT "+projection.valueSql()+", "+projection.typeSql()+" FROM (SELECT CAST("+value+" AS "+nativeType+") AS id) k LIMIT 2")) {
                assertEquals(jdbc,projection.jdbcType(r.getMetaData(),2));assertTrue(r.next());
                assertEquals(value,r.getLong(1));assertFalse(r.wasNull());assertNull(r.getObject(2));assertFalse(r.next());
            }
        }
    }
    @ParameterizedTest @CsvSource({"STRING,'12'","VARCHAR(40),'12'","CHAR(40),'12'","DECIMAL(20),12","DOUBLE,12","BOOLEAN,true","DATE,'2026-09-25'","TIMESTAMP,'2026-09-25 00:00:00'"})
    void convertedTextOrNumericValuesCannotBorrowIntegerEvidence(String nativeType,String value) throws Exception {
        var projection=database.integerKeyProjection("k","id");
        try(var s=connection.createStatement()) {
            s.setQueryTimeout(30);s.setMaxRows(2);
            try(var r=s.executeQuery("SELECT "+projection.valueSql()+", "+projection.typeSql()+" FROM (SELECT CAST('"+value+"' AS "+nativeType+") AS id) k LIMIT 2")) {
                assertEquals(0,projection.jdbcType(r.getMetaData(),2),nativeType);assertTrue(r.next());
            }
        }
    }
    @Test void nullAndSmallByteBudgetCannotBecomeZeroOrTruncatedIdentity() throws Exception {
        for(String value:List.of("NULL","9007199254740993")) {
            var projection=database.keyComponentProjection("k","id",3);
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT "+projection.valueSql()+", "+projection.typeSql()+" FROM (SELECT CAST("+value+" AS BIGINT) AS id) k LIMIT 2")) {
                assertEquals(Types.BIGINT,projection.jdbcType(r.getMetaData(),2));assertTrue(r.next());assertNull(r.getString(1));
            }
        }
    }
    @Test void noRelationGenerationIsInventedFromWorkingProjection() {
        assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,"default.sample"));
    }

    @Test void componentPayloadUsesCompleteUtf8Bytes() throws Exception {
        var projection=database.keyComponentProjection("k","id",21);
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT "+projection.valueSql()+", "+projection.typeSql()+" FROM (SELECT CAST(9007199254740993 AS BIGINT) AS id) k LIMIT 2")) {
            assertEquals(Types.BIGINT,projection.jdbcType(r.getMetaData(),2));assertTrue(r.next());
            assertArrayEquals("9007199254740993".getBytes(java.nio.charset.StandardCharsets.UTF_8),projection.readBytes(r,1));
        }
    }
}
