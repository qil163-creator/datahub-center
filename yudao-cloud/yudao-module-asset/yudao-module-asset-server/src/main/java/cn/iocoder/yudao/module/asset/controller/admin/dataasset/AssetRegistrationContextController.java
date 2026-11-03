package cn.iocoder.yudao.module.asset.controller.admin.dataasset;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetRegistrationNamePreviewReqVO;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetRegistrationContextService;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/asset/data-asset")
@Tag(name = "管理后台 - 资产注册命名上下文")
@Validated
public class AssetRegistrationContextController {

    @Resource
    private AssetRegistrationContextService service;

    /**
     * 读取已启用的真实建模选项，板块与市场分类独立。
     */
    @GetMapping("/registration-context")
    @Operation(summary = "读取已启用的真实建模选项，板块与市场分类独立")
    @PreAuthorize("@ss.hasAnyPermissions('asset:data-asset:create','asset:data-asset:update')")
    public CommonResult<RegistrationContextRespDTO> context(
            @RequestParam(value = "dataDomainId", required = false) Long board,
            @RequestParam(value = "tableType", required = false) String type,
            @RequestParam(value = "subjectDomainId", required = false) Long subject) {
        return CommonResult.success(service.context(board, type, subject));
    }

    /**
     * 按真实候选与持久建模规则生成推荐名称，不修改物理表。
     */
    @PostMapping("/registration-name-preview")
    @Operation(summary = "按真实候选与持久建模规则生成推荐名称，不修改物理表")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:create')")
    public CommonResult<RegistrationNameRespDTO> preview(
            @Valid @RequestBody AssetRegistrationNamePreviewReqVO request) {
        return CommonResult.success(service.preview(request));
    }
}
