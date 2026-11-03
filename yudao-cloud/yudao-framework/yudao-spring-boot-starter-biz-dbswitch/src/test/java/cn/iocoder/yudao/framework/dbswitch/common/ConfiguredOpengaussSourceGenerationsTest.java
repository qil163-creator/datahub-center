package cn.iocoder.yudao.framework.dbswitch.common;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredOpengaussSourceGenerationsTest {
    @Test void deployedPinBindsExactOpengaussEndpointAndPrincipal() {
        String endpoint="jdbc:opengauss://example.invalid:5432/database";String fingerprint=ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.OPENGAUSS,endpoint,"reader");
        var pin=new SourceGenerationPin("dbswitch_control","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222");
        var specification=new ConfiguredSourceGenerations.Specification(){public ProductTypeEnum getProduct(){return ProductTypeEnum.OPENGAUSS;}public boolean isRestoreRotationRequired(){return true;}public String getTargetFingerprint(){return fingerprint;}public String getSchema(){return pin.schema();}public String getInstanceId(){return pin.instanceId();}public String getGeneration(){return pin.generation();}};
        var configured=new ConfiguredSourceGenerations(Map.of("opengauss",specification));assertEquals(pin,configured.find(ProductTypeEnum.OPENGAUSS,endpoint,"reader").orElseThrow());assertTrue(configured.find(ProductTypeEnum.OPENGAUSS,endpoint,"other").isEmpty());assertTrue(configured.find(ProductTypeEnum.POSTGRESQL,endpoint,"reader").isEmpty());
        assertTrue(configured.isCurrent(fingerprint,pin.fingerprint()));
    }
    @ParameterizedTest @ValueSource(strings={"jdbc:opengauss://cluster_alias/database","jdbc:opengauss://one:5432,two:5432/db","jdbc:opengauss://u:p@host:5432/db","jdbc:opengauss://host:5432/db?targetServerType=master","jdbc:opengauss://host:5432/db?autosave=always","jdbc:opengauss://host:5432/","jdbc:opengauss://host:0/db","jdbc:opengauss://host:5432/db#override","jdbc:postgresql://host:5432/db"})
    void ambiguousOrOverridingOpengaussEndpointIsRejected(String endpoint){assertThrows(IllegalArgumentException.class,()->ConfiguredSourceGenerations.validateManagedEndpoint(ProductTypeEnum.OPENGAUSS,endpoint));}
}
