package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterCatalogRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterCatalogSaveReqVO;
import cn.iocoder.yudao.module.governance.service.master.MasterCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据目录")
@RestController
@RequestMapping("/governance/master-catalog")
@Validated
public class MasterCatalogController {

    @Resource
    private MasterCatalogService catalogService;

    /**
     * 创建主数据目录节点
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-主数据目录", subType = "创建主数据目录节点",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建主数据目录节点",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建主数据目录节点")
    @PreAuthorize("@ss.hasPermission('governance:master-catalog:create')")
    public CommonResult<Long> createCatalog(@Valid @RequestBody MasterCatalogSaveReqVO createReqVO) {
        return success(catalogService.createCatalog(createReqVO));
    }

    /**
     * 更新主数据目录节点（父级与类型不可变更）
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-主数据目录", subType = "更新主数据目录节点",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "更新主数据目录节点",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新主数据目录节点")
    @PreAuthorize("@ss.hasPermission('governance:master-catalog:update')")
    public CommonResult<Boolean> updateCatalog(@Valid @RequestBody MasterCatalogSaveReqVO updateReqVO) {
        catalogService.updateCatalog(updateReqVO);
        return success(true);
    }

    /**
     * 删除主数据目录节点（存在子节点或已建模型时拒绝）
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据治理-主数据目录", subType = "删除主数据目录节点",
            bizNo = "{{(#id) ?: 0}}", success = "删除主数据目录节点",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除主数据目录节点")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-catalog:delete')")
    public CommonResult<Boolean> deleteCatalog(@RequestParam("id") Long id) {
        catalogService.deleteCatalog(id);
        return success(true);
    }

    /**
     * 获得主数据目录平铺列表（含主数据节点的模型状态），前端组树
     */
    @GetMapping("/list")
    @Operation(summary = "获得主数据目录平铺列表（前端组树）")
    @PreAuthorize("@ss.hasPermission('governance:master-model:query')")
    public CommonResult<List<MasterCatalogRespVO>> getCatalogList() {
        return success(catalogService.getCatalogList());
    }

}
