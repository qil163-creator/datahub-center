package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LegacySourceGenerationConfigurationTest {
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000", "GBASE8A"})
    void deploymentBindingRetainsExactEndpointPrincipalAndProduct(ProductTypeEnum product) {
        String url = product == ProductTypeEnum.OSCAR ? "jdbc:oscar://127.0.0.1:2003/OSRDB"
                : product == ProductTypeEnum.GBASE8A ? "jdbc:gbase://127.0.0.1:5258/gbase"
                : "jdbc:sqlserver://127.0.0.1:1433;databaseName=replay;loginTimeout=10";
        String instance = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.source-generations.legacy.product=" + product,
                        "yudao.dbswitch.source-generations.legacy.target-fingerprint=" + ConfiguredSourceGenerations.targetFingerprint(product, url, "fixture_reader"),
                        "yudao.dbswitch.source-generations.legacy.schema=REPLAY89",
                        "yudao.dbswitch.source-generations.legacy.instance-id=" + instance,
                        "yudao.dbswitch.source-generations.legacy.generation=" + generation,
                        "yudao.dbswitch.source-generations.legacy.restore-rotation-required=true")
                .run(context -> {
                    assertNull(context.getStartupFailure()); var configured = context.getBean(ConfiguredSourceGenerations.class);
                    var pin = configured.find(product, url, "fixture_reader").orElseThrow();
                    assertEquals(instance, pin.instanceId()); assertEquals(generation, pin.generation());
                    assertTrue(configured.find(product, url, "other").isEmpty());
                    assertTrue(configured.find(product == ProductTypeEnum.OSCAR ? ProductTypeEnum.SQLSERVER2000 : ProductTypeEnum.OSCAR, url, "fixture_reader").isEmpty());
                    assertTrue(configured.isCurrent(ConfiguredSourceGenerations.targetFingerprint(product, url, "fixture_reader"), pin.fingerprint()));
                });
    }
    @Test void oscarDirectEndpointRejectsCredentialsRoutingAndSessionOverrides() {
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.OSCAR, "jdbc:oscar://127.0.0.1:2003/OSRDB");
        for (String url : List.of("jdbc:oscar://one,two:2003/OSRDB", "jdbc:oscar://user:secret@one:2003/OSRDB",
                "jdbc:oscar://one/OSRDB", "jdbc:oscar://one:2003/OSRDB?password=secret", "jdbc:oscar://one:2003/OSRDB;session=sql",
                "jdbc:oscar://one:2003/OSRDB#fragment", "jdbc:postgresql://one:2003/OSRDB")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.OSCAR, url));
            assertFalse(failure.getMessage().contains("secret")); assertFalse(failure.getMessage().contains(url));
        }
    }
    @Test void sql2000SharesOnlyVerifiedSingleEndpointRules() {
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.SQLSERVER2000, "jdbc:sqlserver://127.0.0.1:1433;databaseName=replay");
        for (String url : List.of("jdbc:sqlserver://one;databaseName=replay", "jdbc:sqlserver://one,two:1433;databaseName=replay",
                "jdbc:sqlserver://one:1433;databaseName=replay;password=secret", "jdbc:sqlserver://one:1433;databaseName=replay;failoverPartner=two",
                "jdbc:sqlserver://one:1433;databaseName=replay;databaseName=other"))
            assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.SQLSERVER2000, url));
    }
    @Test void gbaseManagedMarkersUseDirectSingleEndpointWithoutCredentialOrSessionOverrides() {
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.GBASE8A, "jdbc:gbase://127.0.0.1:5258/gbase");
        for (String url : List.of("jdbc:gbase://one/gbase", "jdbc:gbase://one,two:5258/gbase", "jdbc:gbase://one:5258/gbase?password=secret",
                "jdbc:gbase://user:secret@one:5258/gbase", "jdbc:gbase://one:5258/gbase?sessionVariables=sql", "jdbc:mysql://one:5258/gbase"))
            assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.GBASE8A, url));
    }
}
