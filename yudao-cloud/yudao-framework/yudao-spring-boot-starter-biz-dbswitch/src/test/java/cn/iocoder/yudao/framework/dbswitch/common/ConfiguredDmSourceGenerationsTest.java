package cn.iocoder.yudao.framework.dbswitch.common;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredDmSourceGenerationsTest {
    @Test void deployedPinBindsExactDmEndpointAndPrincipal() {
        String endpoint="jdbc:dm://example.invalid:5236";String fingerprint=ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.DM,endpoint,"reader");
        var pin=new SourceGenerationPin("dbswitch_control","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222");
        var specification=new ConfiguredSourceGenerations.Specification(){public ProductTypeEnum getProduct(){return ProductTypeEnum.DM;}public boolean isRestoreRotationRequired(){return true;}public String getTargetFingerprint(){return fingerprint;}public String getSchema(){return pin.schema();}public String getInstanceId(){return pin.instanceId();}public String getGeneration(){return pin.generation();}};
        var configured=new ConfiguredSourceGenerations(Map.of("dm",specification));assertEquals(pin,configured.find(ProductTypeEnum.DM,endpoint,"reader").orElseThrow());assertTrue(configured.find(ProductTypeEnum.DM,endpoint,"other").isEmpty());assertTrue(configured.find(ProductTypeEnum.POSTGRESQL,endpoint,"reader").isEmpty());
        assertTrue(configured.isCurrent(fingerprint,pin.fingerprint()));
    }
    @ParameterizedTest @ValueSource(strings={"jdbc:dm://cluster_alias","jdbc:dm://one:5236,two:5236","jdbc:dm://u:p@host:5236","jdbc:dm://host:5236?rwSeparate=1","jdbc:dm://host:5236?compatibleMode=2","jdbc:dm://host:5236/schema","jdbc:dm://host:0","jdbc:dm://host:5236#override"})
    void ambiguousOrOverridingDmEndpointIsRejected(String endpoint){assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.DM,endpoint));}
}
