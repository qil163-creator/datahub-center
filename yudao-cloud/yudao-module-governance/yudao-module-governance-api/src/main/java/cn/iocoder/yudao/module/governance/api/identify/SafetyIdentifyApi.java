package cn.iocoder.yudao.module.governance.api.identify;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.identify.dto.SafetyIdentifyRuleRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 敏感识别规则只读 RPC，供跨模块读取规则，不写打标、不建工单
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 敏感识别规则")
public interface SafetyIdentifyApi {

    String PREFIX = ApiConstants.PREFIX + "/identify";

    /**
     * 获得启用中的敏感识别规则。
     */
    @GetMapping(PREFIX + "/enabled-rules")
    @Operation(summary = "获得启用中的敏感识别规则")
    CommonResult<List<SafetyIdentifyRuleRespDTO>> getEnabledRules();

}
