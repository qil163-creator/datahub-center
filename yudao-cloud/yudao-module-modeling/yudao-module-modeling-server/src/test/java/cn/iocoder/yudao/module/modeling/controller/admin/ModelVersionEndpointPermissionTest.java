package cn.iocoder.yudao.module.modeling.controller.admin;

import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.BusinessEntityController;
import cn.iocoder.yudao.module.modeling.controller.admin.model.LogicModelController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 版本、结构同步、SQL 解析与关系图接口沿用现有权限码，读写权限不放宽。 */
class ModelVersionEndpointPermissionTest {

    private static String permission(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少接口方法 " + methodName));
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, methodName + " 缺少 @PreAuthorize");
        return annotation.value();
    }

    @Test
    void logicModelEndpointsUseModelPermissions() {
        Map<String, String> expected = Map.of(
                "getVersionList", "@ss.hasPermission('modeling:model:query')",
                "getVersion", "@ss.hasPermission('modeling:model:query')",
                "compareVersion", "@ss.hasPermission('modeling:model:query')",
                "previewSourceSync", "@ss.hasPermission('modeling:model:query')",
                "parseSqlFields", "@ss.hasPermission('modeling:model:query')",
                "applySourceSync",
                "@ss.hasPermission('modeling:model:save') and @ss.hasPermission('modeling:model:publish')");
        expected.forEach((method, value) -> assertEquals(value, permission(LogicModelController.class, method), method));
    }

    @Test
    void businessEntityEndpointsAreReadOnlyQueries() {
        for (String method : new String[]{"relationGraph", "versionList", "version", "compareVersion"}) {
            assertEquals("@ss.hasPermission('modeling:business-entity:query')",
                    permission(BusinessEntityController.class, method), method);
        }
    }
}
