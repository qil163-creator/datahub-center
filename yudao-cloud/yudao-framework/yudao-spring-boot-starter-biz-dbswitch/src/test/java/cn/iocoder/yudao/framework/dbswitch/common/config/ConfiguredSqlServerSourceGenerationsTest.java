package cn.iocoder.yudao.framework.dbswitch.common.config;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.time.LocalTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredSqlServerSourceGenerationsTest {
    private static final String URL="jdbc:sqlserver://127.0.0.1:1433;databaseName=example;encrypt=true;trustServerCertificate=false";
    private record Spec(ProductTypeEnum getProduct,boolean isRestoreRotationRequired,String getTargetFingerprint,
            String getSchema,String getInstanceId,String getGeneration) implements ConfiguredSourceGenerations.Specification { }
    @Test void requiresExactEndpointPrincipalAndExternallyRetainedRotationContract(){
        var spec=new Spec(ProductTypeEnum.SQLSERVER,true,ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.SQLSERVER,URL,"runtime"),"generation","00000001-0000-0000-0000-000000000001","00000001-0000-0000-0000-000000000002");
        var pins=new ConfiguredSourceGenerations(Map.of("source",spec));assertTrue(pins.find(ProductTypeEnum.SQLSERVER,URL,"runtime").isPresent());
        assertTrue(pins.find(ProductTypeEnum.SQLSERVER,URL,"other").isEmpty());assertTrue(pins.find(ProductTypeEnum.SQLSERVER2000,URL,"runtime").isEmpty());
        assertThrows(IllegalArgumentException.class,()->new ConfiguredSourceGenerations(Map.of("bad",new Spec(ProductTypeEnum.SQLSERVER,false,spec.getTargetFingerprint(),spec.getSchema(),spec.getInstanceId(),spec.getGeneration()))));
    }
    @ParameterizedTest @ValueSource(strings={
        "jdbc:sqlserver://host;databaseName=test", "jdbc:sqlserver://host\\instance:1433;databaseName=test",
        "jdbc:sqlserver://user@host:1433;databaseName=test", "jdbc:sqlserver://host:1433;databaseName=test;password=secret",
        "jdbc:sqlserver://host:1433;databaseName=test;failoverPartner=other", "jdbc:sqlserver://host:1433;databaseName=test;applicationIntent=ReadOnly",
        "jdbc:sqlserver://host:1433;databaseName=test;databaseName=other", "jdbc:sqlserver://host:1433;databaseName=test;DatabaseName=other",
        "jdbc:sqlserver://host:1433;databaseName={test;other}", "jdbc:sqlserver://host:1433;databaseName=test;",
        "jdbc:sqlserver://host:1433;encrypt=true", "jdbc:sqlserver://host:0;databaseName=test"})
    void rejectsRoutingCredentialsAliasesAmbiguityAndUnpinnedDatabases(String url){assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.SQLSERVER,url));}
    @Test void time7TransportRetainsOneHundredNanosecondPrecision(){
        var value=TypedCursorValue.of(Types.TIME,"time(7)",LocalTime.parse("23:59:59.1234567"));assertEquals(LocalTime.parse("23:59:59.1234567"),value.jdbcValue());
        assertThrows(IllegalArgumentException.class,()->new TypedCursorValue(Types.TIME,"time(7)","24:00:00"));
    }
}
