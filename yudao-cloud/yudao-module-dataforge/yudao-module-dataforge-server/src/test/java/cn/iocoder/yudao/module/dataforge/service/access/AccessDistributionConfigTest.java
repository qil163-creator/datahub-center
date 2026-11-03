package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AccessDistributionConfigTest {
    @Test void oldDefinitionsKeepTheirDefaultAndDoNotAcquireAnExtraPersistedProperty() {
        var old = AccessTaskConfigCodec.read(json("{}"));
        assertTrue(old.getTarget().getDistributedKeys().isEmpty());
        assertFalse(AccessTaskConfigCodec.write(old).contains("distributedKeys"));
        assertTrue(AccessTaskConfigMapper.toRuntime(old, 2L).getTarget().getDistributedKeys().isEmpty());
    }

    @Test void frozenRunAndRuntimeRetainIndependentOrderedPhysicalNames() {
        var definition = AccessTaskConfigCodec.read(json("{\"distributedKeys\":[\"TenantId\",\"RecordId\"]}"));
        var task = new DataAccessDO(); task.setDataAccessJson(definition);
        String frozen = AccessRunSnapshot.freeze(task);
        var restored = AccessRunSnapshot.restore(frozen).getDataAccessJson();
        var target = AccessTaskConfigMapper.toRuntime(restored, 2L).getTarget();
        target.setTargetProductType(ProductTypeEnum.GREENPLUM);
        assertEquals(List.of("TenantId", "RecordId"), target.copy().getDistributedKeys());
        definition.getTarget().getDistributedKeys().clear();
        restored.getTarget().getDistributedKeys().set(0, "mutated");
        assertEquals(List.of("TenantId", "RecordId"), target.getDistributedKeys());
        assertEquals(List.of("TenantId", "RecordId"), AccessRunSnapshot.restore(frozen)
                .getDataAccessJson().getTarget().getDistributedKeys());
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        assertThrows(IllegalArgumentException.class, target::copy);
    }

    @Test void persistedKeysRejectCoercionDuplicatesInvalidNamesAndIncrementalConfiguration() {
        for (String value : List.of("null", "1", "\"id\"", "[1]", "[true]", "[null]", "[\"\"]",
                "[\" \"]", "[\"id\",\"id\"]", "[\"a\\u0000b\"]"))
            assertThrows(IllegalArgumentException.class,
                    () -> AccessTaskConfigCodec.read(json("{\"distributedKeys\":" + value + "}")), value);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json(
                "{\"distributedKeys\":[\"id\"],\"changeDataSync\":true,\"changeDataSyncType\":\"PK_ORDER_COMPARE\"}")));
        var tooMany = AccessTaskConfigCodec.read(json("{}"));
        var keys = new ArrayList<String>(); for (int i = 0; i < 1025; i++) keys.add("k" + i);
        tooMany.getTarget().setDistributedKeys(keys);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(tooMany));
    }

    private static String json(String target) {
        return "{\"schemaVersion\":1,\"source\":[{}],\"target\":" + target + "}";
    }
}
