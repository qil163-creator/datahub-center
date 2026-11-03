package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfiguredSourceGenerationsTest {
    private static final String URL = "jdbc:mariadb://127.0.0.1:43322/";
    private static final String USER = "fixture_reader";

    @Test void administratorAdmissionUsesTheSameProductEndpointRulesWithoutOpeningConnections() {
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.TIDB,"jdbc:mysql://127.0.0.1:3306/");
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.MARIADB,URL);
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.SYBASE,"jdbc:sybase:Tds:127.0.0.1:5000/tempdb");
        for(String endpoint:List.of("jdbc:mysql://one,two/db","jdbc:mysql://one/db?autoReconnect=true",
                "jdbc:mysql://user:secret@one/db","jdbc:mariadb://one/db")) {
            var failure=assertThrows(IllegalArgumentException.class,
                    ()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.TIDB,endpoint));
            assertFalse(failure.getMessage().contains(endpoint));assertFalse(failure.getMessage().contains("secret"));
        }
        assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.SYBASE,"jdbc:sybase:Tds:one,two:5000/tempdb"));
        assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.SYBASE,"jdbc:sybase:Tds:127.0.0.1:5000/tempdb?session=sql"));
        ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.DB2,"jdbc:db2://127.0.0.1:50000/Q17DB");
        assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.DB2,"jdbc:db2://one,two:50000/Q17DB"));
        assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.DB2,"jdbc:db2://user:secret@127.0.0.1:50000/Q17DB"));
        assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.DB2,"jdbc:db2://127.0.0.1:50000/Q17DB:currentSchema=OTHER;"));
        assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(null,URL));
    }

    @Test void defaultConfigurationIsEmptyAndLegacySpiRemainsTheDefault() throws Exception {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(ConfiguredSourceGenerations.class).find(ProductTypeEnum.MARIADB, URL, USER).isEmpty());
                });
        var database = mock(IDatabaseInterface.class, CALLS_REAL_METHODS);
        var connection = mock(Connection.class);
        doReturn("legacy").when(database).queryRelationFingerprint(connection, "sample");
        assertEquals("legacy", database.queryRelationFingerprint(connection, "sample", null));
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample", pin()));
        verifyNoInteractions(connection);
    }

    @Test void exactEndpointPrincipalAndProductBindAnImmutableSnapshot() {
        var config = config();
        var registry = new ConfiguredSourceGenerations(Map.of("alpha", config));
        var pin = registry.find(ProductTypeEnum.MARIADB, URL, USER).orElseThrow();
        config.setGeneration(UUID.randomUUID().toString()); config.setSchema("changed");
        assertSame(pin, registry.find(ProductTypeEnum.MARIADB, URL, USER).orElseThrow());
        assertTrue(registry.find(ProductTypeEnum.MYSQL, URL, USER).isEmpty());
        assertTrue(registry.find(ProductTypeEnum.MARIADB, URL + "other", USER).isEmpty());
        assertTrue(registry.find(ProductTypeEnum.MARIADB, URL, "other").isEmpty());
        assertNotEquals(pin.fingerprint(), new SourceGenerationPin(pin.schema(), pin.instanceId(), UUID.randomUUID().toString()).fingerprint());
    }

    @Test void ambiguousTargetsAndUnacknowledgedRestoreContractsAreRejected() {
        var first = config(); var second = config();
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSourceGenerations(Map.of("a", first, "b", second)));
        first.setRestoreRotationRequired(false);
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSourceGenerations(Map.of("a", first)));
        first.setRestoreRotationRequired(true); first.setProduct(ProductTypeEnum.UNKNOWN);
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSourceGenerations(Map.of("a", first)));
    }

    @Test void malformedAndUnboundConfigurationCannotBecomeAnOptIn() {
        var config = config(); config.setTargetFingerprint("not-a-fingerprint");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSourceGenerations(Map.of("a", config)));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSourceGenerations(null));
        assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.targetFingerprint(null, URL, USER));
        assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, "", USER));
        assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, URL, ""));
    }

    @Test void springBindingRequiresExplicitRestoreAcknowledgement() {
        var pin = pin();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.source-generations.alpha.product=MARIADB",
                        "yudao.dbswitch.source-generations.alpha.target-fingerprint=" + ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, URL, USER),
                        "yudao.dbswitch.source-generations.alpha.schema=" + pin.schema(),
                        "yudao.dbswitch.source-generations.alpha.instance-id=" + pin.instanceId(),
                        "yudao.dbswitch.source-generations.alpha.generation=" + pin.generation(),
                        "yudao.dbswitch.source-generations.alpha.restore-rotation-required=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(pin, context.getBean(ConfiguredSourceGenerations.class).find(ProductTypeEnum.MARIADB, URL, USER).orElseThrow());
                });
    }

    @Test void aMatchingFingerprintCannotAuthorizeUnverifiedConnectionProfiles() {
        for (String url : new String[]{"jdbc:mariadb:sequential://one,two/db", "jdbc:mariadb://one,two/db",
                "jdbc:mariadb://one/db?autoReconnect=true", "jdbc:mariadb://one/db?transactionReplay=true",
                "jdbc:mariadb://one/db?sessionVariables=autocommit=0", "jdbc:mariadb://user:secret@one/db",
                "jdbc:mariadb://one/db?socketTimeout=1&socketTimeout=2", "jdbc:mariadb://one/db?user=other"}) {
            var value = config(); value.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, url, USER));
            var registry = new ConfiguredSourceGenerations(Map.of("fixture", value));
            var error = assertThrows(IllegalArgumentException.class, () -> registry.find(ProductTypeEnum.MARIADB, url, USER));
            assertFalse(error.getMessage().contains(url)); assertFalse(error.getMessage().contains("secret"));
        }
        ConfiguredSourceGenerations.validateManagedEndpoint("jdbc:mariadb://db-node:3306/demo?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&nullCatalogMeansCurrent=true&connectTimeout=3000");
        ConfiguredSourceGenerations.validateManagedEndpoint("jdbc:mariadb://[::1]:3306/demo?sslMode=verify-full");
    }

    private static SourceGenerationPin pin() { return new SourceGenerationPin("source_metadata", UUID.randomUUID().toString(), UUID.randomUUID().toString()); }
    @Test void mysqlCursorGenerationIsAnExactImmutableDeploymentBinding() {
        String url="jdbc:mysql://127.0.0.1:3306/fixture?tinyInt1isBit=false&connectionTimeZone=UTC";
        var configuration=config();configuration.setProduct(ProductTypeEnum.MYSQL);
        configuration.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MYSQL,url,USER));
        var registry=new ConfiguredSourceGenerations(Map.of("mysql",configuration));
        var original=registry.find(ProductTypeEnum.MYSQL,url,USER).orElseThrow();
        configuration.setGeneration(UUID.randomUUID().toString());
        assertEquals(original,registry.find(ProductTypeEnum.MYSQL,url,USER).orElseThrow());
        assertTrue(registry.find(ProductTypeEnum.MARIADB,url,USER).isEmpty());
        assertTrue(registry.find(ProductTypeEnum.MYSQL,url,USER+"_other").isEmpty());
        assertNotEquals(original.fingerprint(),new ConfiguredSourceGenerations(Map.of("mysql",configuration))
                .find(ProductTypeEnum.MYSQL,url,USER).orElseThrow().fingerprint());
    }
    @Test void mysqlGenerationRejectsRoutingReplayIdentityOverridesAndBooleanConversion() {
        for(String url:List.of("jdbc:mysql:loadbalance://one,two/db","jdbc:mysql://one,two/db",
                "jdbc:mysql://one/db?autoReconnect=true","jdbc:mysql://one/db?sessionVariables=autocommit=0",
                "jdbc:mysql://user:secret@one/db","jdbc:mysql://one/db?user=other",
                "jdbc:mysql://one/db?socketFactory=custom","jdbc:mysql://one/db?tinyInt1isBit=true",
                "jdbc:mysql://one/db?socketTimeout=1&socketTimeout=2")) {
            var configuration=config();configuration.setProduct(ProductTypeEnum.MYSQL);
            configuration.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MYSQL,url,USER));
            var registry=new ConfiguredSourceGenerations(Map.of("mysql",configuration));
            var failure=assertThrows(IllegalArgumentException.class,()->registry.find(ProductTypeEnum.MYSQL,url,USER));
            assertFalse(failure.getMessage().contains(url));assertFalse(failure.getMessage().contains("secret"));
        }
    }
    private static DbSwitchProperties.SourceGeneration config() {
        var value = new DbSwitchProperties.SourceGeneration(); var pin = pin();
        value.setProduct(ProductTypeEnum.MARIADB); value.setRestoreRotationRequired(true);
        value.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, URL, USER));
        value.setSchema(pin.schema()); value.setInstanceId(pin.instanceId()); value.setGeneration(pin.generation());
        return value;
    }

    @Test void tidbGenerationBindsActualDeploymentProductAndDirectEndpoint() {
        for(String url:List.of("jdbc:mysql://127.0.0.1:54069/","jdbc:mysql://127.0.0.1:54069/source?connectionTimeZone=UTC&tinyInt1isBit=false")) {
            var value=config();value.setProduct(ProductTypeEnum.TIDB);
            value.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.TIDB,url,USER));
            var registry=new ConfiguredSourceGenerations(Map.of("tidb",value));
            var pin=registry.find(ProductTypeEnum.TIDB,url,USER).orElseThrow();
            assertTrue(registry.find(ProductTypeEnum.MYSQL,url,USER).isEmpty());
            assertTrue(registry.find(ProductTypeEnum.TIDB,url,USER+"_other").isEmpty());
            value.setGeneration(UUID.randomUUID().toString());
            assertEquals(pin,registry.find(ProductTypeEnum.TIDB,url,USER).orElseThrow());
        }
    }

    @Test void tidbGenerationRejectsRoutingReplayAndSessionOverrides() {
        for(String url:List.of("jdbc:mysql:loadbalance://one,two/source","jdbc:mysql://one,two/source",
                "jdbc:mysql://one/source?autoReconnect=true","jdbc:mysql://one/source?sessionVariables=tidb_snapshot=1",
                "jdbc:mysql://one/source?socketFactory=custom","jdbc:mysql://one/source?tinyInt1isBit=true")) {
            var value=config();value.setProduct(ProductTypeEnum.TIDB);
            value.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.TIDB,url,USER));
            var registry=new ConfiguredSourceGenerations(Map.of("tidb",value));
            assertThrows(IllegalArgumentException.class,()->registry.find(ProductTypeEnum.TIDB,url,USER));
        }
    }
}
