package cn.iocoder.yudao.framework.dbswitch.common;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredOceanbaseSourceGenerationsTest {
    private static ConfiguredSourceGenerations.Specification specification(String fingerprint, SourceGenerationPin pin, boolean rotation) {
        return new ConfiguredSourceGenerations.Specification() {
            public ProductTypeEnum getProduct() { return ProductTypeEnum.OCEANBASE; }
            public boolean isRestoreRotationRequired() { return rotation; }
            public String getTargetFingerprint() { return fingerprint; }
            public String getSchema() { return pin.schema(); }
            public String getInstanceId() { return pin.instanceId(); }
            public String getGeneration() { return pin.generation(); }
        };
    }

    @Test void deployedPinBindsExactOceanbaseEndpointAndTenantPrincipal() {
        String endpoint = "jdbc:oceanbase://example.invalid:2881/app";
        String fingerprint = ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.OCEANBASE, endpoint, "reader@tenant");
        var pin = new SourceGenerationPin("dbswitch_control", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222");
        var configured = new ConfiguredSourceGenerations(Map.of("oceanbase", specification(fingerprint, pin, true)));
        assertEquals(pin, configured.find(ProductTypeEnum.OCEANBASE, endpoint, "reader@tenant").orElseThrow());
        // The tenant is part of the OceanBase principal: another tenant or product never inherits the pin.
        assertTrue(configured.find(ProductTypeEnum.OCEANBASE, endpoint, "reader@other").isEmpty());
        assertTrue(configured.find(ProductTypeEnum.OCEANBASE, endpoint + "2", "reader@tenant").isEmpty());
        assertTrue(configured.find(ProductTypeEnum.MYSQL, endpoint, "reader@tenant").isEmpty());
        assertTrue(configured.find(ProductTypeEnum.ORACLE, endpoint, "reader@tenant").isEmpty());
        assertTrue(configured.isCurrent(fingerprint, pin.fingerprint()));
        assertFalse(configured.isCurrent(fingerprint, new SourceGenerationPin(pin.schema(), pin.instanceId(), "33333333-3333-3333-3333-333333333333").fingerprint()));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSourceGenerations(Map.of("oceanbase", specification(fingerprint, pin, false))));
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc:oceanbase://example.invalid:2881/", "jdbc:oceanbase://example.invalid:2881/app", "jdbc:oceanbase://127.0.0.1:2881"})
    void directEndpointWithOptionalDatabaseIsAccepted(String endpoint) {
        assertDoesNotThrow(() -> ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.OCEANBASE, endpoint));
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc:oceanbase://cluster_alias/app", "jdbc:oceanbase://one:2881,two:2881/app", "jdbc:oceanbase://u:p@host:2881/app",
            "jdbc:oceanbase://host:2881/app?useServerPrepStmts=true", "jdbc:oceanbase://host:2881/app?sessionVariables=ob_read_consistency=WEAK",
            "jdbc:oceanbase://host:2881/app?tinyInt1isBit=false", "jdbc:oceanbase:loadbalance://host:2881/app", "jdbc:oceanbase:oracle://host:2881/app",
            "jdbc:oceanbase://host:0/app", "jdbc:oceanbase://host:2881/app#override", "jdbc:oceanbase://host:2881/a/b", "jdbc:oceanbase://host:2881/a b",
            "jdbc:mysql://host:2881/app", "jdbc:oceanbase://"})
    void ambiguousRoutingOrOverridingOceanbaseEndpointIsRejected(String endpoint) {
        assertThrows(IllegalArgumentException.class, () -> ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.OCEANBASE, endpoint));
    }

    @Test void configuredPinOnAnUnmanagedEndpointIsRefusedInsteadOfBeingIgnored() {
        String endpoint = "jdbc:oceanbase://host:2881/app?sessionVariables=ob_read_consistency=WEAK";
        var pin = new SourceGenerationPin("dbswitch_control", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222");
        var configured = new ConfiguredSourceGenerations(Map.of("oceanbase",
                specification(ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.OCEANBASE, endpoint, "reader@tenant"), pin, true)));
        assertThrows(IllegalArgumentException.class, () -> configured.find(ProductTypeEnum.OCEANBASE, endpoint, "reader@tenant"));
    }
}
