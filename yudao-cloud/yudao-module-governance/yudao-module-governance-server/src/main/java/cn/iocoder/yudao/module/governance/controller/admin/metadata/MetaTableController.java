package cn.iocoder.yudao.module.governance.controller.admin.metadata;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.*;
import cn.iocoder.yudao.module.governance.service.metadata.MetaObjectService;
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

/**
 * 管理后台 - 表元数据（最新元数据浏览与维护）
 *
 * @author Li
 */
@Tag(name = "管理后台 - 表元数据")
@RestController
@RequestMapping("/governance/meta-table")
@Validated
public class MetaTableController {

    @Resource
    private MetaObjectService objectService;

    /**
     * 获得表元数据分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得表元数据分页")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<PageResult<MetaTableRespVO>> getTablePage(@Valid MetaTablePageReqVO pageReqVO) {
        return success(objectService.getTablePage(pageReqVO));
    }

    /**
     * 获得表元数据详情。
     */
    @GetMapping("/get")
    @Operation(summary = "获得表元数据详情")
    @Parameter(name = "id", description = "表元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<MetaTableRespVO> getTableDetail(@RequestParam("id") Long id) {
        return success(objectService.getTableDetail(id));
    }

    /**
     * 维护表的业务/管理属性（逐属性记录手工变更明细）。
     */
    @PutMapping("/update-biz")
    @LogRecord(type = "数据治理-表元数据", subType = "维护表的业务/管理属性（逐属性记录手工变更明细）",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "维护表的业务/管理属性（逐属性记录手工变更明细）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "维护表的业务/管理属性（逐属性记录手工变更明细）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> updateTableBiz(@Valid @RequestBody MetaTableUpdateBizReqVO reqVO) {
        objectService.updateTableBiz(reqVO);
        return success(true);
    }

    /**
     * 确认删除表（仅待确认删除状态；历史与快照保留）。
     */
    @PutMapping("/confirm-delete")
    @LogRecord(type = "数据治理-表元数据", subType = "确认删除表（仅待确认删除状态；历史与快照保留）",
            bizNo = "{{(#id) ?: 0}}", success = "确认删除表（仅待确认删除状态；历史与快照保留）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "确认删除表（仅待确认删除状态；历史与快照保留）")
    @Parameter(name = "id", description = "表元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> confirmDeleteTable(@RequestParam("id") Long id) {
        objectService.confirmDeleteTable(id);
        return success(true);
    }

    /**
     * 发布表（仅更新发布状态，保留独立定版）。
     */
    @PutMapping("/publish")
    @LogRecord(type = "数据治理-表元数据", subType = "发布表（仅更新发布状态，保留独立定版）",
            bizNo = "{{(#id) ?: 0}}", success = "发布表（仅更新发布状态，保留独立定版）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布表（仅更新发布状态，保留独立定版）")
    @Parameter(name = "id", description = "表元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:publish')")
    public CommonResult<Boolean> publishTable(@RequestParam("id") Long id,
            @RequestParam("stateRevision") Integer stateRevision) {
        objectService.publishTable(id, stateRevision);
        return success(true);
    }

    /**
     * 取消发布（保留定版镜像与资产引用）。
     */
    @PutMapping("/unpublish")
    @LogRecord(type = "数据治理-表元数据", subType = "取消发布（保留定版镜像与资产引用）",
            bizNo = "{{(#id) ?: 0}}", success = "取消发布（保留定版镜像与资产引用）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "取消发布（保留定版镜像与资产引用）")
    @Parameter(name = "id", description = "表元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:publish')")
    public CommonResult<Boolean> unpublishTable(@RequestParam("id") Long id,
            @RequestParam("stateRevision") Integer stateRevision) {
        objectService.unpublishTable(id, stateRevision);
        return success(true);
    }

    /**
     * 获得表的字段列表（不含已删除字段）。
     */
    @GetMapping("/column-list")
    @Operation(summary = "获得表的字段列表（不含已删除字段）")
    @Parameter(name = "tableId", description = "表元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<List<MetaColumnRespVO>> getColumnList(@RequestParam("tableId") Long tableId) {
        return success(objectService.getColumnList(tableId));
    }

    /**
     * 获得当前字段及固定落标标准详情。
     */
    @GetMapping("/column-get")
    @Operation(summary = "获得当前字段及固定落标标准详情")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<MetaColumnRespVO> getColumnDetail(@RequestParam("id") Long id) {
        return success(objectService.getColumnDetail(id));
    }

    /**
     * 维护字段的业务/管理属性（逐属性记录手工变更明细）。
     */
    @PutMapping("/update-column-biz")
    @LogRecord(type = "数据治理-表元数据", subType = "维护字段的业务/管理属性（逐属性记录手工变更明细）",
            bizNo = "{{(#reqVO.id) ?: 0}}", success = "维护字段的业务/管理属性（逐属性记录手工变更明细）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "维护字段的业务/管理属性（逐属性记录手工变更明细）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> updateColumnBiz(@Valid @RequestBody MetaColumnUpdateBizReqVO reqVO) {
        objectService.updateColumnBiz(reqVO);
        return success(true);
    }

    /**
     * 获得表的版本快照列表（不含快照体）。
     */
    @GetMapping("/snapshot-list")
    @Operation(summary = "获得表的版本快照列表（不含快照体）")
    @Parameter(name = "tableId", description = "表元数据编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<List<MetaSnapshotRespVO>> getSnapshotList(@RequestParam("tableId") Long tableId) {
        return success(objectService.getSnapshotList(tableId));
    }

    /**
     * 获得快照详情（含全结构快照体）。
     */
    @GetMapping("/get-snapshot")
    @Operation(summary = "获得快照详情（含全结构快照体）")
    @Parameter(name = "id", description = "快照编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<MetaSnapshotRespVO> getSnapshot(@RequestParam("id") Long id) {
        return success(objectService.getSnapshot(id));
    }

    /**
     * 获得元数据变更明细分页。
     */
    @GetMapping("/change-page")
    @Operation(summary = "获得元数据变更明细分页")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<PageResult<MetaChangeRespVO>> getChangePage(@Valid MetaChangePageReqVO pageReqVO) {
        return success(objectService.getChangePage(pageReqVO));
    }

    /**
     * 批量认责（技术/业务负责人，逐表留痕）。
     */
    @PutMapping("/batch-owner")
    @LogRecord(type = "数据治理-表元数据", subType = "批量认责（技术/业务负责人，逐表留痕）",
            bizNo = "{{(#reqVO.ids) ?: 0}}", success = "批量认责（技术/业务负责人，逐表留痕）",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量认责（技术/业务负责人，逐表留痕）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> batchAssignOwner(@Valid @RequestBody MetaBatchOwnerReqVO reqVO) {
        objectService.batchAssignOwner(reqVO);
        return success(true);
    }

    /**
     * 指定结构版本定版；业务属性按确认时冻结。
     */
    @PutMapping("/finalize")
    @LogRecord(type = "数据治理-表元数据", subType = "指定结构版本定版；业务属性按确认时冻结",
            bizNo = "{{(#request.id) ?: 0}}", success = "指定结构版本定版；业务属性按确认时冻结",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "指定结构版本定版；业务属性按确认时冻结")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:publish')")
    public CommonResult<Boolean> finalizeTable(@Valid @RequestBody MetaTableFinalizeReqVO request) {
        objectService.finalizeTable(request);
        return success(true);
    }

    /**
     * 撤销定版，不改变发布状态与已存在的资产快照引用。
     */
    @PutMapping("/cancel-finalize")
    @LogRecord(type = "数据治理-表元数据", subType = "撤销定版，不改变发布状态与已存在的资产快照引用",
            bizNo = "{{(#request.id) ?: 0}}", success = "撤销定版，不改变发布状态与已存在的资产快照引用",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "撤销定版，不改变发布状态与已存在的资产快照引用")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:publish')")
    public CommonResult<Boolean> cancelFinalizeTable(@Valid @RequestBody MetaTableStateReqVO request) {
        objectService.cancelFinalizeTable(request);
        return success(true);
    }

    /**
     * 定版元数据分页（展示冻结镜像）。
     */
    @GetMapping("/finalized-page")
    @Operation(summary = "定版元数据分页（展示冻结镜像）")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<PageResult<MetaTableRespVO>> finalizedPage(@Valid MetaTablePageReqVO request) {
        return success(objectService.getFinalizedTablePage(request));
    }

    /**
     * 读取定版镜像；不补入当前技术或业务字段。
     */
    @GetMapping("/finalized-get")
    @Operation(summary = "读取定版镜像；不补入当前技术或业务字段")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<MetaFinalizedDetailRespVO> finalizedGet(@RequestParam("id") Long id) {
        return success(objectService.getFinalizedTableDetail(id));
    }

    /**
     * 对比同表任意两个结构版本。
     */
    @GetMapping("/compare-snapshots")
    @Operation(summary = "对比同表任意两个结构版本")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:query')")
    public CommonResult<MetaSnapshotCompareRespVO> compareSnapshots(@RequestParam("tableId") Long tableId,
            @RequestParam("fromSnapshotId") Long fromSnapshotId, @RequestParam("toSnapshotId") Long toSnapshotId) {
        return success(objectService.compareSnapshots(tableId, fromSnapshotId, toSnapshotId));
    }

    /**
     * 删除未发布且未关联资产目录的表；历史版本保留。
     */
    @PutMapping("/delete")
    @LogRecord(type = "数据治理-表元数据", subType = "删除未发布且未关联资产目录的表；历史版本保留",
            bizNo = "{{(#request.id) ?: 0}}", success = "删除未发布且未关联资产目录的表；历史版本保留",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除未发布且未关联资产目录的表；历史版本保留")
    @PreAuthorize("@ss.hasPermission('governance:meta-table:update')")
    public CommonResult<Boolean> deleteTable(@Valid @RequestBody MetaTableStateReqVO request) {
        objectService.deleteTable(request);
        return success(true);
    }

}
