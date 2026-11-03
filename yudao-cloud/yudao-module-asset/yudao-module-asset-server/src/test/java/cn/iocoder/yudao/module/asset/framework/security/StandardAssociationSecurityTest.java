package cn.iocoder.yudao.module.asset.framework.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo.StandardAssociationRespVO;
import cn.iocoder.yudao.module.asset.framework.security.config.StandardAssociationSecurityConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StandardAssociationSecurityTest {
    @Test void directAdminEntryStripsIdentityAndPermissionControlHeadersBeforeContextFilters() throws Exception {
        var registration = new StandardAssociationSecurityConfiguration().standardAssociationIdentityFilter(new WebProperties());
        assertEquals(List.of("/admin-api/asset/standard-association/*"), List.copyOf(registration.getUrlPatterns()));
        assertTrue(registration.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
        var request = new MockHttpServletRequest();
        request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, "forged");
        request.addHeader(WebFrameworkUtils.HEADER_TENANT_ID, "123");
        request.addHeader(WebFrameworkUtils.HEADER_VISIT_TENANT_ID, "456");
        request.addHeader(DataPermissionRequestInterceptor.ENABLE_HEADER_NAME, "false");
        request.addHeader("Authorization", "Bearer current-user");
        registration.getFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var http = (HttpServletRequest) req;
            assertNull(http.getHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER));
            assertNull(http.getHeader(WebFrameworkUtils.HEADER_TENANT_ID));
            assertNull(http.getHeader(WebFrameworkUtils.HEADER_VISIT_TENANT_ID));
            assertNull(http.getHeader(DataPermissionRequestInterceptor.ENABLE_HEADER_NAME));
            assertEquals("Bearer current-user", http.getHeader("Authorization"));
        });
    }

    @Test void assetIdsStayLosslessAndDatesUseExistingMillisecondsWireContract() {
        var row = new StandardAssociationRespVO(); row.setId(9007199254740993L);
        row.setCreateTime(LocalDateTime.of(2026, 10, 16, 12, 0));
        var mapper = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var json = mapper.readTree(mapper.writeValueAsString(row));
        assertTrue(json.get("id").isString()); assertEquals("9007199254740993", json.get("id").asString());
        assertTrue(json.get("createTime").isNumber());
    }
}
