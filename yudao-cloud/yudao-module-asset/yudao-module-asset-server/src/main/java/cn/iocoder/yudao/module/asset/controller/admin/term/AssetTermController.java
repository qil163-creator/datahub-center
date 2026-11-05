package cn.iocoder.yudao.module.asset.controller.admin.term;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermRespVO;
import cn.iocoder.yudao.module.asset.service.term.AssetTermService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 资产字段关联治理术语")
@RestController
@RequestMapping("/asset/term")
@Validated
public class AssetTermController {

    @Resource
    private AssetTermService termService;

    /**
     * 治理模块启用中的业务术语，供资产字段选择。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得启用中的治理业务术语（最多50条，可按关键字搜索）")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:term')")
    public CommonResult<List<AssetTermRespVO>> getSimpleTermList(
            @RequestParam(value = "keyword", required = false) @Size(max = 100) String keyword) {
        return success(termService.getSimpleTermList(keyword));
    }

    /**
     * termId 是治理业务术语编号；为空表示解绑。
     */
    @BusinessOperateLog(type = "数据资产-资产字段关联治理术语", bizId = "#reqVO.assetId")
    @PutMapping("/bind-column")
    @Operation(summary = "资产字段绑定/解除治理业务术语")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:term')")
    public CommonResult<Boolean> bindColumn(@Valid @RequestBody AssetTermBindReqVO reqVO) {
        termService.bindColumn(reqVO);
        return success(true);
    }
}
