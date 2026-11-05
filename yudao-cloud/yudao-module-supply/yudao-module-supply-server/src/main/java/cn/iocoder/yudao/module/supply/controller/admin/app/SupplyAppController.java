package cn.iocoder.yudao.module.supply.controller.admin.app;

import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.*;
import cn.iocoder.yudao.module.supply.service.app.vo.SupplyAppCreateResult;
import cn.iocoder.yudao.module.supply.service.app.SupplyAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据服务-应用管理")
@RestController
@RequestMapping("/supply/app")
@Validated
public class SupplyAppController {

    @Resource
    private SupplyAppService appService;

    @Resource
    private cn.iocoder.yudao.module.supply.service.app.SupplyEtlGrantHistoryService etlGrantHistory;

    /**
     * 按编号倒序查看独立 ETL 授权的不可变审计历史。
     *
     * @param request 请求参数
     * @return 供应ETL授权审计分页响应数据
     */
    @GetMapping("/api-auth/etl-audit-page")
    @Operation(summary = "按编号倒序查看独立 ETL 授权的不可变审计历史")
    // 仅限平台内应用管理范围；不是普通应用成员入口。
    @PreAuthorize("@ss.hasPermission('supply:app:query') && @ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<SupplyEtlGrantAuditPageRespVO> etlAuditHistory(@Valid SupplyEtlGrantAuditPageReqVO request) {
        return success(etlGrantHistory.history(request));
    }

    /**
     * 创建应用（自动生成秘钥）。
     *
     * @param createReqVO 创建参数
     * @return 供应应用响应数据
     */
    @LogRecord(type = "数据服务-应用管理", subType = "创建应用（自动生成秘钥）", bizNo = "{{(#_ret.data.id) ?: 0}}",
            success = "创建了应用【{{#createReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建应用（自动生成秘钥）")
    @ApiAccessLog(responseEnable = false, sanitizeKeys = {"secret", "plainSecret", "appSecret"})
    @PreAuthorize("@ss.hasPermission('supply:app:create')")
    public CommonResult<SupplyAppRespVO> createApp(@Valid @RequestBody SupplyAppSaveReqVO createReqVO) {
        SupplyAppCreateResult result = appService.createApp(createReqVO);
        SupplyAppRespVO vo = BeanUtils.toBean(result.getApp(), SupplyAppRespVO.class);
        vo.setSecret(result.getPlainSecret());
        return success(vo);
    }

    /**
     * 更新应用。
     *
     * @param updateReqVO 更新参数
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "更新应用", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了应用【{{#auditObjectName}}】，名称由【{{#auditObjectName}}】改为【{{#updateReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新应用")
    @PreAuthorize("@ss.hasPermission('supply:app:update')")
    public CommonResult<Boolean> updateApp(@Valid @RequestBody SupplyAppSaveReqVO updateReqVO) {
        var auditBefore = appService.getApp(updateReqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + updateReqVO.getId() : auditBefore.getName());

        appService.updateApp(updateReqVO);
        return success(true);
    }

    /**
     * 重置应用秘钥。
     *
     * @param id 记录编号
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "重置应用秘钥", bizNo = "{{(#id) ?: 0}}",
            success = "重置了应用【{{#auditObjectName}}】（编号{{#id}}）的密钥", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/reset-secret")
    @Operation(summary = "重置应用秘钥")
    @Parameter(name = "id", description = "编号", required = true)
    @ApiAccessLog(responseEnable = false, sanitizeKeys = {"secret", "plainSecret", "appSecret"})
    @PreAuthorize("@ss.hasPermission('supply:app:update')")
    public CommonResult<String> resetAppSecret(@RequestParam("id") Long id) {
        var auditBefore = appService.getApp(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        return success(appService.resetAppSecret(id));
    }

    /**
     * 删除应用（级联删除 API 授权）。
     *
     * @param id 记录编号
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "删除应用（级联删除 API 授权）", bizNo = "{{(#id) ?: 0}}",
            success = "删除了应用【{{#auditObjectName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除应用（级联删除 API 授权）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:app:delete')")
    public CommonResult<Boolean> deleteApp(@RequestParam("id") Long id) {
        var auditBefore = appService.getApp(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        appService.deleteApp(id);
        return success(true);
    }

    /**
     * 获得应用。
     *
     * @param id 记录编号
     * @return 供应应用响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得应用")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('supply:app:query')")
    public CommonResult<SupplyAppRespVO> getApp(@RequestParam("id") Long id) {
        SupplyAppRespVO vo = BeanUtils.toBean(appService.getApp(id), SupplyAppRespVO.class);
        vo.setSecret(null);
        return success(vo);
    }

    /**
     * 获得应用分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 应用分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得应用分页")
    @PreAuthorize("@ss.hasPermission('supply:app:query')")
    public CommonResult<PageResult<SupplyAppRespVO>> getAppPage(@Valid SupplyAppPageReqVO pageReqVO) {
        PageResult<SupplyAppRespVO> result = BeanUtils.toBean(appService.getAppPage(pageReqVO), SupplyAppRespVO.class);
        result.getList().forEach(item -> item.setSecret(null));
        return success(result);
    }

    /**
     * 启用、暂停或撤销应用。
     *
     * @param id 记录编号
     * @param appStatus 应用状态
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "启用、暂停或撤销应用", bizNo = "{{(#id) ?: 0}}",
            success = "将应用【{{#auditObjectName}}】状态设为【{{#appStatus}}】（1启用、0暂停、2撤销）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "启用、暂停或撤销应用")
    @PreAuthorize("@ss.hasPermission('supply:app:update')")
    public CommonResult<Boolean> updateAppStatus(@RequestParam("id") Long id,
            @RequestParam("appStatus") Integer appStatus) {
        var auditBefore = appService.getApp(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        appService.updateAppStatus(id, appStatus);
        return success(true);
    }

    // ==================== API 授权 ====================

    /**
     * 获得应用的 API 授权列表。
     *
     * @param appId 应用编号
     * @return 应用的 API 授权列表结果集合
     */
    @GetMapping("/api-auth/list")
    @Operation(summary = "获得应用的 API 授权列表")
    @Parameter(name = "appId", description = "应用编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:app:query')")
    public CommonResult<List<AppApiRespVO>> getAppApiList(@RequestParam("appId") Long appId) {
        return success(appService.getAppApiList(appId));
    }

    /**
     * 按编号获得应用 API 授权及实际状态。
     *
     * @param id 记录编号
     * @return 应用 API 授权响应数据
     */
    @GetMapping("/api-auth/get")
    @Operation(summary = "按编号获得应用 API 授权及实际状态")
    @PreAuthorize("@ss.hasPermission('supply:app:query')")
    public CommonResult<AppApiRespVO> getAppApi(@RequestParam("id") @NotNull @Min(1) Long id) {
        return success(appService.getAppApi(id));
    }

    /**
     * 新增应用 API 授权。
     *
     * @param createReqVO 创建参数
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "新增应用 API 授权", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "为应用【{{#auditObjectName}}】（编号{{#createReqVO.appId}}）授权API【编号{{#createReqVO.apiId}}】，授权记录【{{#_ret.data}}】，永久有效【{{#createReqVO.foreverFlag == 1}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/api-auth/create")
    @Operation(summary = "新增应用 API 授权")
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Long> createAppApi(@Valid @RequestBody AppApiSaveReqVO createReqVO) {
        var auditBefore = appService.getApp(createReqVO.getAppId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + createReqVO.getAppId() : auditBefore.getName());

        return success(appService.createAppApi(createReqVO));
    }

    /**
     * 修改普通独立管理员 API 授权的有效期和描述。
     *
     * @param reqVO 请求参数
     * @return 供应应用
     */
    @PutMapping("/api-auth/update")
    @Operation(summary = "修改普通独立管理员 API 授权的有效期和描述")
    // 沿用平台内全应用管理范围；不修改市场来源、ETL 批次或批准合同。
    @PreAuthorize("@ss.hasPermission('supply:app:query') && @ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Boolean> updateAppApi(@Valid @RequestBody AppApiUpdateReqVO reqVO) {
        appService.updateAppApi(reqVO);
        return success(true);
    }

    /**
     * 明确重新启用应用 API 授权聚合。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "明确重新启用应用 API 授权聚合", bizNo = "{{(#id) ?: 0}}",
            success = "重新启用了应用API授权【编号{{#id}}】，原聚合修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/api-auth/enable")
    @Operation(summary = "明确重新启用应用 API 授权聚合")
    // 沿用本控制器管理授权的平台内全应用范围；不是应用 owner/成员自助授权入口。
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Boolean> enableAppApi(@RequestParam("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision) {
        appService.enableAppApi(id, expectedRevision);
        return success(true);
    }

    /**
     * 整体启用或禁用应用 API 授权（按聚合版本防止旧页面覆盖）。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @param disabled 禁用状态
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "整体启用或禁用应用 API 授权（按聚合版本防止旧页面覆盖）", bizNo = "{{(#id) ?: 0}}",
            success = "将应用API授权【编号{{#id}}】的整体禁用状态设为【{{#disabled}}】，原聚合修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/api-auth/control")
    @Operation(summary = "整体启用或禁用应用 API 授权（按聚合版本防止旧页面覆盖）")
    // 沿用本控制器管理授权的平台内全应用范围；不接受来源系统标识，逐来源生命周期属于来源系统。
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Boolean> setGrantControl(@RequestParam("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision,
            @RequestParam("disabled") Boolean disabled) {
        appService.setGrantControl(id, expectedRevision, Boolean.TRUE.equals(disabled));
        return success(true);
    }

    /**
     * 永久关闭管理端 ADMIN 授权（终态；市场来源须由来源系统撤销）。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "永久关闭管理端 ADMIN 授权（终态；市场来源须由来源系统撤销）", bizNo = "{{(#id) ?: 0}}",
            success = "永久撤销了独立管理员API授权【编号{{#id}}】，原聚合修订【{{#expectedRevision}}】，不影响市场来源的独立生命周期", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/api-auth/revoke")
    @Operation(summary = "永久关闭管理端 ADMIN 授权（终态；市场来源须由来源系统撤销）")
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Boolean> revokeAdminAppApi(@RequestParam("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision) {
        appService.revokeAdminAppApi(id, expectedRevision);
        return success(true);
    }

    /**
     * 整体禁用应用 API 授权（保留来源记录）。
     *
     * @param id 记录编号
     * @return 供应应用
     */
    @LogRecord(type = "数据服务-应用管理", subType = "整体禁用应用 API 授权（保留来源记录）", bizNo = "{{(#id) ?: 0}}",
            success = "整体禁用了应用API授权【编号{{#id}}】，来源记录继续保留", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/api-auth/delete")
    @Operation(summary = "整体禁用应用 API 授权（保留来源记录）")
    @Parameter(name = "id", description = "授权编号", required = true)
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Boolean> deleteAppApi(@RequestParam("id") Long id) {
        appService.deleteAppApi(id);
        return success(true);
    }

}
