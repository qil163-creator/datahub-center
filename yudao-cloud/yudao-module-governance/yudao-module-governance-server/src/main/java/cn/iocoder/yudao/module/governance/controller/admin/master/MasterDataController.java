package cn.iocoder.yudao.module.governance.controller.admin.master;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.governance.service.master.MasterDataImportService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.IMPORT;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataRowsReqVO;
import cn.iocoder.yudao.module.governance.service.master.MasterDataService;
import cn.iocoder.yudao.module.governance.service.master.MasterDataChangeService;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataChangeSubmitReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataMutationRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelDataCountRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import cn.iocoder.yudao.module.governance.service.master.MasterModelService;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 主数据记录维护")
@RestController
@RequestMapping("/governance/master-data")
@Validated
public class MasterDataController {

    @Resource
    private MasterDataService masterDataService;

    @Resource
    private MasterDataChangeService changeService;

    @Resource
    private MasterModelService modelService;

    @Resource
    private MasterDataImportService importService;

    @GetMapping("/import-template")
    @Operation(summary = "下载维护模型的主数据导入模板")
    @PreAuthorize("@ss.hasPermission('governance:master-data:create')")
    public void downloadImportTemplate(@RequestParam Long modelId, HttpServletResponse response) throws IOException {
        importService.writeTemplate(modelId, response);
    }

    @PostMapping("/import")
    @Operation(summary = "整批导入主数据，按模型的审批设置提交")
    @PreAuthorize("@ss.hasPermission('governance:master-data:create')")
    @ApiAccessLog(operateType = IMPORT)
    @LogRecord(type = "数据治理-主数据记录维护", subType = "批量导入主数据",
            bizNo = "{{#_ret.data.id}}", success = "模型【{getMasterModelName{#modelId}}】导入主数据",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    public CommonResult<MasterDataMutationRespVO> importRows(@RequestParam Long modelId,
            @RequestParam @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId,
            @RequestParam MultipartFile file) {
        return success(importService.submit(modelId, requestId, file));
    }

    /**
     * 分页查询可进入维护的已发布模型（平台内共享）。
     */
    @GetMapping("/model-page")
    @Operation(summary = "分页查询可进入维护的已发布模型（平台内共享）")
    @PreAuthorize("@ss.hasPermission('governance:master-data:query')")
    public CommonResult<PageResult<MasterModelRespVO>> getMaintenanceModelPage(@Valid MasterModelPageReqVO req) {
        return success(modelService.getMaintenanceModelPage(req));
    }

    /**
     * 获取维护模型详情（仅已发布且结构结果已确认，平台内共享）。
     */
    @GetMapping("/model")
    @Operation(summary = "获取维护模型详情（仅已发布且结构结果已确认，平台内共享）")
    @PreAuthorize("@ss.hasPermission('governance:master-data:query')")
    public CommonResult<MasterModelRespVO> getMaintenanceModel(@RequestParam("id") Long id) {
        return success(modelService.getMaintenanceModel(id));
    }

    /**
     * 按需查询维护模型数据量（平台内共享）。
     */
    @GetMapping("/model-data-count")
    @Operation(summary = "按需查询维护模型数据量（平台内共享）")
    @PreAuthorize("@ss.hasPermission('governance:master-data:query')")
    public CommonResult<MasterModelDataCountRespVO> getMaintenanceDataCount(@RequestParam("id") Long id) {
        return success(modelService.getMaintenanceDataCount(id));
    }

    /**
     * 幂等提交主数据变更，审批通过后生效。
     */
    @PostMapping("/submit")
    @LogRecord(type = "数据治理-主数据记录维护", subType = "提交主数据变更",
            bizNo = "{{(#_ret.data.id) ?: 0}}", success = "提交了模型【{getMasterModelName{#reqVO.modelId}}】的主数据【{{#reqVO.operation == 'CREATE' ? '新增' : (#reqVO.operation == 'UPDATE' ? '修改' : (#reqVO.operation == 'DELETE' ? '删除' : (#reqVO.operation == 'MERGE' ? '合并' : #reqVO.operation)))}}】变更，受理状态【{getStateLabel{#_ret.data.status}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "幂等提交主数据变更，审批通过后生效")
    @PreAuthorize("(#reqVO.operation == 'CREATE' and @ss.hasPermission('governance:master-data:create')) or "
            + "(#reqVO.operation == 'UPDATE' and @ss.hasPermission('governance:master-data:update')) or "
            + "(#reqVO.operation == 'DELETE' and @ss.hasPermission('governance:master-data:delete'))")
    public CommonResult<MasterDataMutationRespVO> submit(@Valid @RequestBody MasterDataChangeSubmitReqVO reqVO) {
        return success(changeService.submit(reqVO));
    }

    /**
     * 分页查询主数据记录（模型须已发布）
     */
    @PostMapping("/page")
    @Operation(summary = "分页查询主数据记录（条件在请求体中）")
    @PreAuthorize("@ss.hasPermission('governance:master-data:query')")
    public CommonResult<MasterDataPageRespVO> getMasterDataPage(
            @Valid @RequestBody MasterDataPageReqVO pageReqVO) {
        return success(masterDataService.getMasterDataPage(pageReqVO));
    }

    /**
     * 批量新增主数据记录。
     */
    @PostMapping("/add")
    @LogRecord(type = "数据治理-主数据记录维护", subType = "提交主数据新增变更",
            bizNo = "{{(#_ret.data.id) ?: 0}}", success = "提交了模型【{getMasterModelName{#reqVO.modelId}}】的主数据新增变更，受理状态【{getStateLabel{#_ret.data.status}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量新增主数据记录")
    @PreAuthorize("@ss.hasPermission('governance:master-data:create')")
    public CommonResult<MasterDataMutationRespVO> addMasterData(@Valid @RequestBody MasterDataRowsReqVO reqVO) {
        return success(submitLegacy(reqVO, "CREATE"));
    }

    /**
     * 按主键批量更新主数据记录。
     */
    @PostMapping("/update")
    @LogRecord(type = "数据治理-主数据记录维护", subType = "提交主数据修改变更",
            bizNo = "{{(#_ret.data.id) ?: 0}}", success = "提交了模型【{getMasterModelName{#reqVO.modelId}}】的主数据修改变更，受理状态【{getStateLabel{#_ret.data.status}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "按主键批量更新主数据记录")
    @PreAuthorize("@ss.hasPermission('governance:master-data:update')")
    public CommonResult<MasterDataMutationRespVO> updateMasterData(@Valid @RequestBody MasterDataRowsReqVO reqVO) {
        return success(submitLegacy(reqVO, "UPDATE"));
    }

    /**
     * 按主键批量删除主数据记录。
     */
    @PostMapping("/delete")
    @LogRecord(type = "数据治理-主数据记录维护", subType = "提交主数据删除变更",
            bizNo = "{{(#_ret.data.id) ?: 0}}", success = "提交了模型【{getMasterModelName{#reqVO.modelId}}】的主数据删除变更，受理状态【{getStateLabel{#_ret.data.status}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "按主键批量删除主数据记录")
    @PreAuthorize("@ss.hasPermission('governance:master-data:delete')")
    public CommonResult<MasterDataMutationRespVO> deleteMasterData(@Valid @RequestBody MasterDataRowsReqVO reqVO) {
        return success(submitLegacy(reqVO, "DELETE"));
    }

    /**
     * 提交旧版。
     */
    private MasterDataMutationRespVO submitLegacy(MasterDataRowsReqVO reqVO, String operation) {
        MasterDataChangeSubmitReqVO req = BeanUtils.toBean(reqVO, MasterDataChangeSubmitReqVO.class);
        req.setOperation(operation);
        return changeService.submit(req);
    }

}
