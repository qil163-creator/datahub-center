package cn.iocoder.yudao.module.asset.controller.admin.dataasset;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 资产注册")
@RestController
@RequestMapping("/asset/data-asset")
@Validated
public class AssetRegistrationController {

    @Resource
    private AssetRegistrationService registrationService;

    /**
     * 读取真实元数据/已发布 API 注册候选，平台注册人员可见，不代表技术交付授权。
     */
    @GetMapping("/registration-candidates")
    @Operation(summary = "读取真实元数据/已发布 API 注册候选，平台注册人员可见，不代表技术交付授权")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:create')")
    public CommonResult<PageResult<AssetRegistrationCandidateRespVO>> candidates(
            @Valid AssetRegistrationCandidatePageReqVO req) {
        return success(registrationService.candidates(req));
    }

    /**
     * 从真实提供方注册 API 或文件资产，尚未验证的交付保持不可用。
     */
    @LogRecord(type = "数据资产-资产注册", subType = "从真实提供方注册 API 或文件资产，尚未验证的交付保持不可用", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "从真实提供方注册 API 或文件资产，尚未验证的交付保持不可用", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/register-generic")
    @Operation(summary = "从真实提供方注册 API 或文件资产，尚未验证的交付保持不可用")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:create')")
    public CommonResult<List<Long>> register(@Valid @RequestBody AssetGenericRegisterReqVO req) {
        return success(registrationService.register(req));
    }
}
