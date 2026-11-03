package cn.iocoder.yudao.module.supply.controller.open;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiGatewayService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_AUTH_FAILED;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_NOT_AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayControllerAcceptanceTest {

    @Test
    void t33OpenGatewayDoesNotRequireLoginAndReturnsData() {
        GatewayController controller = new GatewayController();
        DataApiGatewayService gatewayService = mock(DataApiGatewayService.class);
        ReflectionTestUtils.setField(controller, "gatewayService", gatewayService);
        DataApiExecuteRespVO body = new DataApiExecuteRespVO();
        body.setRows(List.of(Map.of("id", 1)));
        when(gatewayService.invoke(eq("v1"), eq("/orders"), eq("GET"), anyMap(),
                isNull(), isNull(), nullable(String.class), nullable(String.class),
                nullable(String.class), anyMap())).thenReturn(body);
        MockHttpServletRequest request = request("/supply/gateway/services/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CommonResult<DataApiExecuteRespVO> result = controller.invoke("v1", request, response, null);

        assertThat(result.getData().getRows()).hasSize(1);
        assertThat(response.getHeader("X-Trace-Id")).isNotBlank();
        assertThat(request.getHeader("Authorization")).isNull();
    }

    @Test
    void t34TraceIdSurvivesAuthFailure() {
        GatewayController controller = new GatewayController();
        DataApiGatewayService gatewayService = mock(DataApiGatewayService.class);
        ReflectionTestUtils.setField(controller, "gatewayService", gatewayService);
        when(gatewayService.invoke(anyString(), anyString(), anyString(), anyMap(),
                nullable(Integer.class), nullable(Integer.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyMap()))
                .thenThrow(new ServiceException(DATA_API_AUTH_FAILED));
        MockHttpServletRequest request = request("/supply/gateway/services/v1/orders");
        request.addHeader("X-Trace-Id", "trace-dept-b1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.invoke("v1", request, response, null))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_012));
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-dept-b1");
    }

    @Test
    void t34GrantFailureUsesDifferentCode() {
        GatewayController controller = new GatewayController();
        DataApiGatewayService gatewayService = mock(DataApiGatewayService.class);
        ReflectionTestUtils.setField(controller, "gatewayService", gatewayService);
        when(gatewayService.invoke(anyString(), anyString(), anyString(), anyMap(),
                nullable(Integer.class), nullable(Integer.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyMap()))
                .thenThrow(new ServiceException(DATA_API_NOT_AUTHORIZED));
        MockHttpServletRequest request = request("/supply/gateway/services/v1/orders");
        HttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.invoke("v1", request, response, null))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_040_013));
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.addHeader("X-AppKey", "app-key");
        return request;
    }
}
