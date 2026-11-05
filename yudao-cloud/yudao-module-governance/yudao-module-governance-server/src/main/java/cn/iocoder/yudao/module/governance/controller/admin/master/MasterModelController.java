package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelDataCountRespVO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.service.master.MasterModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据模型")
@RestController
@RequestMapping("/governance/master-model")
@Validated
public class MasterModelController {

    @Resource
    private MasterModelService modelService;

    /**
     * 创建主数据模型草稿，仅保存平台定义，字段与主键可在发布前完善。
     */
    @PostMapping("/create")
    @LogRecord(type = "数据治理-主数据模型", subType = "保存主数据模型草稿（不创建物理表）",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "创建了主数据模型【{{(#createReqVO.tableCn) ?: '未设置'}}】，编码【{{(#createReqVO.tableName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存主数据模型草稿（不创建物理表）")
    @PreAuthorize("@ss.hasPermission('governance:master-model:create')")
    public CommonResult<Long> createModel(@Valid @RequestBody MasterModelSaveReqVO createReqVO) {
        return success(modelService.createModel(createReqVO));
    }

    /**
     * 保存模型修改并保持原发布状态；曾发布模型只能修改业务信息，结构仍冻结。
     */
    @PutMapping("/update")
    @LogRecord(type = "数据治理-主数据模型", subType = "保存主数据模型修改（结构变更保存为草稿）",
            bizNo = "{{(#updateReqVO.id) ?: 0}}", success = "修改了主数据模型，原名称【{{(#auditObjectName) ?: '编号' + #updateReqVO.id}}】，提交名称【{{(#updateReqVO.tableCn) ?: '未设置'}}】，提交编码【{{(#updateReqVO.tableName) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "保存主数据模型修改（结构变更保存为草稿）")
    @PreAuthorize("@ss.hasPermission('governance:master-model:update')")
    public CommonResult<Boolean> updateModel(@Valid @RequestBody MasterModelSaveReqVO updateReqVO) {
        captureAuditContext(updateReqVO.getId());
        modelService.updateModel(updateReqVO);
        return success(true);
    }

    /**
     * 删除主数据模型（已发布或存在派发任务时拒绝）
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除主数据模型")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:delete')")
    public CommonResult<Boolean> deleteModel(@RequestParam("id") Long id) {
        modelService.deleteModel(id);
        return success(true);
    }

    /**
     * 批量删除主数据草稿或已下线模型（保留物理表）。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除主数据草稿或已下线模型（保留物理表）")
    @PreAuthorize("@ss.hasPermission('governance:master-model:delete')")
    public CommonResult<Boolean> deleteModelList(@RequestParam("ids") @NotEmpty @Size(max = 100) List<Long> ids) {
        modelService.deleteModelList(ids);
        return success(true);
    }

    /**
     * 分页查询主数据模型。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询主数据模型")
    @PreAuthorize("@ss.hasPermission('governance:master-model:query')")
    public CommonResult<PageResult<MasterModelRespVO>> getModelPage(@Valid MasterModelPageReqVO req) {
        return success(modelService.getModelPage(req));
    }

    /**
     * 与主数据查询一样在平台内共享；仅按需读取已绑定物理表，不接受客户端表名。
     */
    @GetMapping("/data-count")
    @Operation(summary = "按需查询模型物理表数据量（平台内共享，与主数据查询范围一致）")
    @Parameter(name = "id", description = "模型编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:query') and @ss.hasPermission('governance:master-data:query')")
    public CommonResult<MasterModelDataCountRespVO> getDataCount(@RequestParam("id") Long id) {
        return success(modelService.getDataCount(id));
    }

    /**
     * 统计主数据模型状态。
     */
    @GetMapping("/statistics")
    @Operation(summary = "统计主数据模型状态")
    @PreAuthorize("@ss.hasPermission('governance:master-model:query')")
    public CommonResult<MasterModelStatisticsRespVO> getStatistics(
            @RequestParam(value = "domainId", required = false) Long domainId) {
        return success(modelService.getStatistics(domainId));
    }

    /**
     * 查询主数据模型精简列表。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "查询主数据模型精简列表")
    @PreAuthorize("@ss.hasPermission('governance:master-model:query') or "
            + "@ss.hasPermission('governance:master-code-rule:query') or @ss.hasPermission('governance:master-approval:query') or "
            + "@ss.hasPermission('governance:master-change-log:query') or @ss.hasPermission('governance:master-data:query')")
    public CommonResult<List<MasterModelRespVO>> getSimpleList() {
        return success(modelService.getSimpleList());
    }

    /**
     * 获得主数据模型（含字段）
     */
    @GetMapping("/get")
    @Operation(summary = "获得主数据模型（含字段）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:query') or @ss.hasPermission('governance:master-data:query') or "
            + "@ss.hasPermission('governance:master-code-rule:query') or @ss.hasPermission('governance:master-approval:query')")
    public CommonResult<MasterModelRespVO> getModel(@RequestParam("id") Long id) {
        return success(modelService.getModel(id));
    }

    /**
     * 获得目录节点下的主数据模型（含字段），未建模时返回 null
     */
    @GetMapping("/get-by-catalog")
    @Operation(summary = "获得目录节点下的主数据模型（未建模返回空）")
    @Parameter(name = "catalogId", description = "目录编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:query')")
    public CommonResult<MasterModelRespVO> getModelByCatalog(@RequestParam("catalogId") Long catalogId) {
        return success(modelService.getModelByCatalog(catalogId));
    }

    /**
     * 发布模型：在绑定数据源中创建物理表
     */
    @PutMapping("/release")
    @Operation(summary = "发布主数据模型（首次建表，结构草稿增量同步）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:release')")
    public CommonResult<Boolean> releaseModel(@RequestParam("id") Long id) {
        modelService.releaseModel(id);
        return success(true);
    }

    /**
     * 下线模型并保留物理表（存在派发任务时拒绝）
     */
    @PutMapping("/cancel-release")
    @LogRecord(type = "数据治理-主数据模型", subType = "下线主数据模型（保留物理表和数据）",
            bizNo = "{{(#id) ?: 0}}", success = "下线了主数据模型【{{(#auditObjectName) ?: '编号' + #id}}】，保留物理表与数据",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "下线主数据模型（保留物理表和数据）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:release')")
    public CommonResult<Boolean> cancelReleaseModel(@RequestParam("id") Long id) {
        captureAuditContext(id);
        modelService.cancelReleaseModel(id);
        return success(true);
    }

    /**
     * 核查不确定发布结果并确认物理表。
     */
    @PutMapping("/verify-release")
    @Operation(summary = "核查不确定发布结果并确认物理表")
    @PreAuthorize("@ss.hasPermission('governance:master-model:release')")
    public CommonResult<Boolean> verifyRelease(@RequestParam("id") Long id) {
        modelService.verifyRelease(id);
        return success(true);
    }

    /**
     * 获得已发布模型精简列表（派发任务选择用）
     */
    @GetMapping("/released-list")
    @Operation(summary = "获得已发布模型精简列表（派发任务选择用）")
    @PreAuthorize("@ss.hasPermission('governance:master-model:query') or "
            + "@ss.hasPermission('governance:master-distribute:query') or @ss.hasPermission('governance:master-data:query') or "
            + "@ss.hasPermission('governance:master-code-rule:query')")
    public CommonResult<List<MasterModelRespVO>> getReleasedModelList() {
        return success(modelService.getReleasedModelList());
    }

    /**
     * 从数据源已有表提取字段定义（建模时拾取字段用）
     */
    @GetMapping("/table-columns")
    @Operation(summary = "从数据源已有表提取字段定义（建模拾取字段用）")
    @Parameter(name = "databaseId", description = "数据源编号", required = true)
    @Parameter(name = "tableName", description = "表名", required = true)
    @PreAuthorize("@ss.hasPermission('governance:master-model:query')")
    public CommonResult<List<MasterColumnVO>> getTableColumns(@RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName) {
        return success(modelService.getTableColumns(databaseId, tableName));
    }


    /** 只保留本次操作所需的名称、状态与修订，不改变原业务校验。 */
    private void captureAuditContext(Long id) {
        LogRecordContext.putVariable("auditObjectName", "编号" + id);
        LogRecordContext.putVariable("auditBeforeStatus", "未设置");
        LogRecordContext.putVariable("auditBeforeRevision", "未设置");
        try {
            var auditBefore = modelService.getModel(id);
            if (auditBefore != null) {
                LogRecordContext.putVariable("auditObjectName", auditBefore.getTableCn() == null
                        ? "编号" + id : auditBefore.getTableCn());
                LogRecordContext.putVariable("auditBeforeStatus", auditBefore.getStatus() == null
                        ? "未设置" : String.valueOf(auditBefore.getStatus()));
                LogRecordContext.putVariable("auditBeforeRevision", auditBefore.getVersion() == null
                        ? "未设置" : String.valueOf(auditBefore.getVersion()));
            }
        } catch (RuntimeException auditReadFailure) {
            // 补充审计信息失败时，仍由原写入方法给出业务校验与异常结果。
        }
    }
}
