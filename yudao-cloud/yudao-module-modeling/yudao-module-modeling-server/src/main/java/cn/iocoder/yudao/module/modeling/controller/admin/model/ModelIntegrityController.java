package cn.iocoder.yudao.module.modeling.controller.admin.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelIntegrityCheckRespVO;
import cn.iocoder.yudao.module.modeling.service.model.ModelIntegrityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 模型归属完整性检测")
@RestController
@RequestMapping("/modeling/model-integrity")
@Validated
public class ModelIntegrityController {

    @Resource
    private ModelIntegrityService integrityService;

    /**
     * 只读完整性检测（不定主域、不改模型）。
     *
     * @param modelType 模型类型
     * @param id 记录编号
     * @return 模型完整性检查响应数据
     */
    @GetMapping("/check")
    @Operation(summary = "只读完整性检测（不定主域、不改模型）")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<ModelIntegrityCheckRespVO> check(
            @Parameter(description = "LOGIC/PHYSICAL", required = true) @RequestParam("modelType") String modelType,
            @Parameter(description = "模型编号", required = true) @jakarta.validation.constraints.Positive @RequestParam("id") Long id) {
        return success(integrityService.check(modelType, id));
    }

}
