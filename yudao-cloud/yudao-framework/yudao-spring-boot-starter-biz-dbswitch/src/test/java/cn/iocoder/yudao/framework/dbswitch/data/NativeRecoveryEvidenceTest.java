package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class NativeRecoveryEvidenceTest {
    @Test void resultOwnsAnImmutableReconciliationSnapshotAndJsonPreservesUnconfirmedIdentity() throws Exception {
        var resources = new ArrayList<NativeRecoveryResource>();
        resources.add(new NativeRecoveryResource(ProductTypeEnum.MONGODB, "sample", "dbswitch_stage_run",
                NativeRecoveryResource.Role.STAGING, null));
        var result = new DbSwitchTableResult(); result.setNativePlanDigest("a".repeat(64)); result.setNativeRecoveryResources(resources);
        resources.clear(); assertEquals(1, result.getNativeRecoveryResources().size());
        assertThrows(UnsupportedOperationException.class, () -> result.getNativeRecoveryResources().clear());
        var json = new ObjectMapper().valueToTree(result);
        assertEquals("a".repeat(64), json.path("nativePlanDigest").textValue());
        assertEquals("dbswitch_stage_run", json.path("nativeRecoveryResources").get(0).path("name").textValue());
        assertTrue(json.path("nativeRecoveryResources").get(0).path("expectedPhysicalIdentity").isNull());
        assertFalse(result.getNativeRecoveryResources().get(0).toString().contains("dbswitch_stage_run"));
    }
    @Test void nativeDdlExclusivityIsExplicitAndIndependentFromPostgresSequenceOwnership() {
        var target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.MONGODB);
        assertFalse(target.getExclusiveTargetDdlOwnership());
        target.setExclusiveTargetDdlOwnership(true);
        var copy = target.copy(); assertTrue(copy.getExclusiveTargetDdlOwnership());
        assertFalse(copy.getExclusiveTargetOwnership()); assertFalse(copy.getPreserveIdentityValues());
    }
}
