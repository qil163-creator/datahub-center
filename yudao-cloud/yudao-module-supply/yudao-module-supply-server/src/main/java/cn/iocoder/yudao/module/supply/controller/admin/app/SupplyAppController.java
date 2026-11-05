package cn.iocoder.yudao.module.supply.controller.admin.app;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#result.id")
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#updateReqVO.id")
    @PutMapping("/update")
    @Operation(summary = "更新应用")
    @PreAuthorize("@ss.hasPermission('supply:app:update')")
    public CommonResult<Boolean> updateApp(@Valid @RequestBody SupplyAppSaveReqVO updateReqVO) {
        appService.updateApp(updateReqVO);
        return success(true);
    }

    /**
     * 重置应用秘钥。
     *
     * @param id 记录编号
     * @return 供应应用
     */
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
    @PutMapping("/reset-secret")
    @Operation(summary = "重置应用秘钥")
    @Parameter(name = "id", description = "编号", required = true)
    @ApiAccessLog(responseEnable = false, sanitizeKeys = {"secret", "plainSecret", "appSecret"})
    @PreAuthorize("@ss.hasPermission('supply:app:update')")
    public CommonResult<String> resetAppSecret(@RequestParam("id") Long id) {
        return success(appService.resetAppSecret(id));
    }

    /**
     * 删除应用（级联删除 API 授权）。
     *
     * @param id 记录编号
     * @return 供应应用
     */
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
    @DeleteMapping("/delete")
    @Operation(summary = "删除应用（级联删除 API 授权）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:app:delete')")
    public CommonResult<Boolean> deleteApp(@RequestParam("id") Long id) {
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
    @PutMapping("/update-status")
    @Operation(summary = "启用、暂停或撤销应用")
    @PreAuthorize("@ss.hasPermission('supply:app:update')")
    public CommonResult<Boolean> updateAppStatus(@RequestParam("id") Long id,
            @RequestParam("appStatus") Integer appStatus) {
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#result")
    @PostMapping("/api-auth/create")
    @Operation(summary = "新增应用 API 授权")
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Long> createAppApi(@Valid @RequestBody AppApiSaveReqVO createReqVO) {
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
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
    @BusinessOperateLog(type = "数据服务-应用管理", bizId = "#id")
    @DeleteMapping("/api-auth/delete")
    @Operation(summary = "整体禁用应用 API 授权（保留来源记录）")
    @Parameter(name = "id", description = "授权编号", required = true)
    @PreAuthorize("@ss.hasAnyPermissions('supply:app:grant', 'supply:app:update')")
    public CommonResult<Boolean> deleteAppApi(@RequestParam("id") Long id) {
        appService.deleteAppApi(id);
        return success(true);
    }

}
