package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.etl.core.validation.EtlValidationResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlComponentVerifyReqVO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlComponentVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - ETL 组件校验 Controller。
 */
@Tag(name = "管理后台 - ETL 组件校验")
@RestController
@RequestMapping({"/dataforge/etl-component", "/dataforge/etlComponetVerify"})
@Validated
public class EtlComponentVerificationController {

    @Resource
    private EtlComponentVerificationService verificationService;

    /**
     * 校验单个组件或完整流程画布；兼容 qData 的 componet 拼写。
     *
     * @param request 请求参数
     * @return 组件校验结果
     */
    @PostMapping({"/verify", "/componet"})
    @Operation(summary = "校验单个组件或完整流程画布")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<EtlValidationResult> verify(@Valid @RequestBody EtlComponentVerifyReqVO request) {
        return success(verificationService.verify(request));
    }
}
