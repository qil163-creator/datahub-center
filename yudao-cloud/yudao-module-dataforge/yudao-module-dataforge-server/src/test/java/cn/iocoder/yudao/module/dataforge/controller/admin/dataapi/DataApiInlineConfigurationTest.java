package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataApiInlineConfigurationTest {
    @Test void taskRequestCascadesValidationToInlineApi() {
        var request = new DataAccessSaveReqVO(); request.setTaskName("接口采集");
        var mapping = new ApiAccessMappingVO(); mapping.setApiConfig(new DataApiSaveReqVO()); request.setApiMappings(List.of(mapping));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().startsWith("apiMappings[0].apiConfig.")));
        }
    }

    @Test void paginationAndHttpContractRejectMalformedConfigurations() {
        var api = new DataApiSaveReqVO(); api.setApiName("接口"); api.setRequestMethod("GET"); api.setRequestUrl("https://example.invalid/data");
        assertTrue(api.isRequestConfigurationValid());
        api.setIfPage(1); assertFalse(api.isRequestConfigurationValid());
        api.setPageKey("page"); api.setPageSizeKey("size"); assertTrue(api.isRequestConfigurationValid());
        api.setRequestUrl("file:///fixture"); assertFalse(api.isRequestConfigurationValid());
        api.setRequestUrl("https://example.invalid/data"); api.setRequestMethod("DELETE"); assertFalse(api.isRequestConfigurationValid());
    }

    @Test void jsonContractKeepsInlineConfigurationAndOmitsValidationHelper() {
        var api = new DataApiSaveReqVO(); api.setApiName("接口"); api.setRequestMethod("GET"); api.setRequestUrl("https://example.invalid/data");
        var mapping = new ApiAccessMappingVO(); mapping.setApiConfig(api);
        String json = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(mapping);
        assertTrue(json.contains("\"apiConfig\"")); assertTrue(json.contains("https://example.invalid/data"));
        assertFalse(json.contains("requestConfigurationValid")); assertFalse(json.contains("sourceId"));
        assertFalse(json.contains("\"orgId\"")); assertFalse(json.contains("\"id\""));
    }

    @Test void testAndParseEndpointsAllowTaskAndEtlEditors() throws Exception {
        for (String method : List.of("testDataApi", "fetchData")) {
            String permission = DataApiController.class.getMethod(method, DataApiSaveReqVO.class).getAnnotation(PreAuthorize.class).value();
            assertTrue(permission.contains("hasAnyPermissions"));
            assertFalse(permission.contains("dataforge:data-api:"));
            for (String allowed : List.of("dataforge:access:create", "dataforge:access:update", "dataforge:etl:save"))
                assertTrue(permission.contains(allowed));
        }
    }
}
