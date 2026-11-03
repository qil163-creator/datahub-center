package cn.iocoder.yudao.module.governance.api.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** 以当前用户的真实访问令牌读取平台共享分类，提供方重新校验敏感清单权限。 */
@FeignClient(name = ApiConstants.NAME, contextId = "sensitiveClassificationReferenceApi",
        path = "${yudao.web.admin-api.prefix:/admin-api}")
@Tag(name = "管理后台 - 敏感清单分类引用")
public interface SensitiveClassificationReferenceApi {

    String PREFIX = "/governance/sensitive-classification-reference";

    /**
     * 读取敏感清单可以引用的共享分类树，保留目录及停用项的状态。
     */
    @GetMapping(PREFIX + "/list")
    @Operation(summary = "读取敏感清单分类引用树")
    CommonResult<List<SafetyClassificationRespDTO>> getClassifications(
            @RequestHeader("Authorization") String authorization);

    /**
     * 读取用于敏感清单写入校验的分类事实，分类缺失时返回空。
     */
    @GetMapping(PREFIX + "/get")
    @Operation(summary = "读取敏感清单分类引用详情")
    CommonResult<SafetyClassificationRespDTO> getClassification(
            @RequestParam("id") @Positive Long id, @RequestHeader("Authorization") String authorization);
}
