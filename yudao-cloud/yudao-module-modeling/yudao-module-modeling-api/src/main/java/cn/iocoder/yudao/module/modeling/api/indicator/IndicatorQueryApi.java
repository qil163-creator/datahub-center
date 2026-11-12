package cn.iocoder.yudao.module.modeling.api.indicator;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiReqDTO;
import cn.iocoder.yudao.module.modeling.api.indicator.dto.IndicatorQueryApiRespDTO;
import cn.iocoder.yudao.module.modeling.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 指标数据查询 RPC：供数据服务（supply）把指标发布为 API。
 * <p>
 * 调用方已完成应用认证与接口授权；本服务按 {@code viewerUserId} 对结果做动态脱敏，脱敏服务不可用时不返回数据。
 */
@FeignClient(name = ApiConstants.NAME, contextId = "indicatorQueryApi")
@Tag(name = "RPC 服务 - 指标数据查询")
public interface IndicatorQueryApi {

    String PREFIX = ApiConstants.PREFIX + "/indicator-query";

    /**
     * 查询指标值。
     */
    @PostMapping(PREFIX + "/query")
    @Operation(summary = "查询指标值")
    CommonResult<IndicatorQueryApiRespDTO> query(@Valid @RequestBody IndicatorQueryApiReqDTO request);
}
