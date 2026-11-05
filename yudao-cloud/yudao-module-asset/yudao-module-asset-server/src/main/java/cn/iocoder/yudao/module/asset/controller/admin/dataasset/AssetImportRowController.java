package cn.iocoder.yudao.module.asset.controller.admin.dataasset;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.service.dataasset.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 迁入资产数据维护")
@RestController
@RequestMapping("/asset/data-asset/import-row")
@Validated
public class AssetImportRowController {

    @Resource
    private AssetImportSnapshotService snapshots;

    @Resource
    private AssetCatalogService catalog;

    @Resource
    private DataAssetService assets;

    /**
     * 以修订号维护本地迁入行并记录前后变更。
     */
    @BusinessOperateLog(type = "数据资产-迁入资产数据维护", bizId = "#req.id")
    @PostMapping("/update")
    @Operation(summary = "以修订号维护本地迁入行并记录前后变更")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AssetImportRowUpdateReqVO req) {
        snapshots.updateRow(req, catalog.prepareMaintenanceAuthorization());
        return success(true);
    }

    /**
     * 在资产维护范围内查看迁入行修改记录。
     */
    @GetMapping("/log-page")
    @Operation(summary = "在资产维护范围内查看迁入行修改记录")
    @PreAuthorize("@ss.hasPermission('asset:data-asset:update')")
    public CommonResult<PageResult<AssetImportRowLogRespVO>> logs(@Valid AssetImportRowLogPageReqVO req) {
        assets.getAssetDetail(req.getId(), false);
        return success(snapshots.logs(req.getId(), req.getRowId(), req.getPageNo(), req.getPageSize(),
                req.getCreateTime(), req.getCreatorName()));
    }
}
