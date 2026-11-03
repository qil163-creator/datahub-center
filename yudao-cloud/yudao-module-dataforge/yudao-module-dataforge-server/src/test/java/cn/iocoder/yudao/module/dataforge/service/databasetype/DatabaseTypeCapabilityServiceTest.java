package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeSaveReqVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTypeCapabilityServiceTest {
    @Test
    void cursorDirectionsComeFromTheRegistryAndRetainTheirDeclaredScope() {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var service = new DatabaseTypeCapabilityService();
            ReflectionTestUtils.setField(service, "runtime", runtime);
            var type = DatabaseTypeDO.builder().typeCode("GBASE8A")
                    .description("南大通用分析型数据库").remark("管理员备注").build();
            var result = service.describe(type);
            assertEquals("南大通用分析型数据库", result.getDescription());
            assertEquals("管理员备注", result.getRemark());
            assertEquals(java.util.List.of("GBASE8A", "POSTGRESQL"), result.getCursorTargets());
            assertEquals("DECLARED", result.getCapabilities().get("WRITE_UPSERT").status());
            assertEquals("GBASE8A_86243_EXPRESS_LOCKED_SINGLE_MERGE_EXACT_STAGING",
                    result.getCapabilities().get("WRITE_UPSERT").reason());
            type.setTypeCode("CLICKHOUSE");
            assertEquals(java.util.List.of("CLICKHOUSE"), service.describe(type).getCursorTargets());
            type.setTypeCode("TDENGINE");
            assertEquals(java.util.List.of("POSTGRESQL", "TDENGINE"), service.describe(type).getCursorTargets());
        }
    }

    @Test
    void descriptionAccepts512CharactersAndRejectsLongerText() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new DatabaseTypeSaveReqVO();
            request.setDescription("描".repeat(512));
            assertTrue(validator.validateProperty(request, "description").isEmpty());
            request.setDescription("描".repeat(513));
            assertEquals(1, validator.validateProperty(request, "description").size());
        }
    }

    @Test
    void unregisteredTypeCannotCertifyOrIdentifyAProduct() {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var service = new DatabaseTypeCapabilityService();
            ReflectionTestUtils.setField(service, "runtime", runtime);
            var type = DatabaseTypeDO.builder().id(9007199254740993L).typeCode("unknown-product").build();
            var result = service.describe(type);
            assertEquals(type.getId(), result.getId());
            assertNull(result.getProductId());
            assertTrue(result.getCapabilities().isEmpty());
            assertTrue(result.getCursorTargets().isEmpty());
            type.setTypeCode("PG");
            result = service.describe(type);
            assertEquals("POSTGRESQL", result.getProductId());
            assertEquals("DECLARED", result.getCapabilities().get("METADATA").status());
            assertEquals("DECLARED", result.getCapabilities().get("WRITE_UPSERT").status());
            assertTrue(result.getCursorTargets().contains("POSTGRESQL"));
            assertTrue(result.getCapabilities().values().stream().noneMatch(value -> "VERIFIED".equals(value.status())));
        }
    }

}
