package cn.iocoder.yudao.module.governance.api.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermClientConfiguration;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;


/** 复用已配置资产服务证书的治理只读通道；调用方负责资产对象授权。 */
@FeignClient(name = ApiConstants.NAME, contextId = "safetyClassificationApi",
        configuration = StandardTermClientConfiguration.class)
@Tag(name = "RPC 服务 - 敏感清单分类定义")
public interface SafetyClassificationApi {

    String PREFIX = ApiConstants.PREFIX + "/safety-classification";

    /**
     * 读取平台共享治理分类树的扁平投影。
     */
    @GetMapping(PREFIX + "/list")
    @Operation(summary = "读取平台共享治理分类树的扁平投影")
    CommonResult<List<SafetyClassificationRespDTO>> getClassifications();

    /**
     * 核验敏感清单引用的治理分类，缺失时返回空。
     */
    @GetMapping(PREFIX + "/get")
    @Operation(summary = "核验敏感清单引用的治理分类，缺失时返回空")
    CommonResult<SafetyClassificationRespDTO> getClassification(@RequestParam("id") Long id);

}
