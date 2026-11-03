package cn.iocoder.yudao.module.asset.api.sensitive;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.api.sensitive.dto.*;
import cn.iocoder.yudao.module.asset.enums.ApiConstants;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 仅向认证治理服务提供规则工作台的敏感清单分页，不参与业务数据查询权限。 */
@FeignClient(name = ApiConstants.NAME, contextId = "assetSensitivePolicyApi",
        configuration = AssetSensitivePolicyClientConfiguration.class)
public interface AssetSensitivePolicyApi {

    String PREFIX = ApiConstants.PREFIX + "/sensitive-policy";

    /**
     * 读取规则分页。
     */
    @PostMapping(PREFIX + "/rule-page")
    CommonResult<PageResult<AssetSensitiveFactRespDTO>> getRulePage(
            @Valid @RequestBody AssetRuleSensitivePageReqDTO request);
}
