package cn.iocoder.yudao.module.governance.controller.admin.permission;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionDatabaseRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionTableRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyPermissionLegacyBindReqVO;
import cn.iocoder.yudao.module.governance.service.permission.SafetyPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据权限")
@RestController
@RequestMapping("/governance/safety-permission")
@Validated
public class SafetyPermissionController {

    @Resource
    private SafetyPermissionService permissionService;

    @Resource
    private cn.iocoder.yudao.module.governance.service.permission.SafetyWritePermissionService writePermissionService;

    /**
     * 预检所选角色的已保存写授权并显示执行 SQL，不实际执行。
     */
    @PostMapping("/write-preflight")
    @Operation(summary = "预检所选角色的已保存写授权并显示执行 SQL，不实际执行")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:write-preflight')")
    public CommonResult<cn.iocoder.yudao.module.governance.api.permission.dto.SafetyWritePermissionRespDTO> preflight(
            @Valid @RequestBody cn.iocoder.yudao.module.governance.controller.admin.permission.vo.SafetyWritePreflightReqVO request) {
        return success(writePermissionService.preflight(request.getDatabaseId(), request.getSql(),
                request.getParameterValues(), request.getRoleId()));
    }

    /**
     * 保存表与字段权限配置。
     */
    @PostMapping("/save")
    @Operation(summary = "保存表与字段权限配置")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:update')")
    public CommonResult<Boolean> saveConfig(@Valid @RequestBody SafetyPermissionSaveReqVO saveReqVO) {
        permissionService.saveConfig(saveReqVO);
        return success(true);
    }

    /**
     * 获得指定表、角色的权限配置。
     */
    @GetMapping("/config")
    @Operation(summary = "获得指定表、角色的权限配置")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:query')")
    public CommonResult<SafetyPermissionSaveReqVO> getConfig(
            @RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName,
            @RequestParam("roleId") Long roleId,
            @RequestParam("schemaName") String schemaName,
            @RequestParam("tableReference") String tableReference) {
        return success(permissionService.getConfig(databaseId, schemaName, tableName, tableReference, roleId));
    }

    /**
     * 核验后将历史策略绑定到精确物理模式和表，不覆盖或自动合并。
     */
    @PostMapping("/bind-legacy")
    @Operation(summary = "核验后将历史策略绑定到精确物理模式和表，不覆盖或自动合并")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:update')")
    public CommonResult<Boolean> bindLegacy(@Valid @RequestBody SafetyPermissionLegacyBindReqVO request) {
        permissionService.bindLegacy(request);
        return success(true);
    }

    /**
     * 获得角色已配置的全部表权限。
     */
    @GetMapping("/role")
    @Operation(summary = "获得角色已配置的全部表权限")
    @Parameter(name = "roleId", description = "角色编号", required = true)
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:query')")
    public CommonResult<List<SafetyPermissionSaveReqVO>> getRolePermissions(@RequestParam("roleId") Long roleId) {
        return success(permissionService.getRolePermissions(roleId));
    }

    /**
     * 获得可配置权限的数据源列表。
     */
    @GetMapping("/database-list")
    @Operation(summary = "获得可配置权限的数据源列表")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:query')")
    public CommonResult<List<SafetyPermissionDatabaseRespVO>> getDatabaseList() {
        return success(permissionService.getDatabaseList());
    }

    /**
     * 获得数据源下的表清单。
     */
    @GetMapping("/table-list")
    @Operation(summary = "获得数据源下的表清单")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:query')")
    public CommonResult<List<SafetyPermissionTableRespVO>> getTableList(@RequestParam("databaseId") Long databaseId) {
        return success(permissionService.getTableList(databaseId));
    }

    /**
     * 获得表字段列表（附带元数据注释与分级）。
     */
    @GetMapping("/column-list")
    @Operation(summary = "获得表字段列表（附带元数据注释与分级）")
    @PreAuthorize("@ss.hasPermission('governance:safety-permission:query')")
    public CommonResult<List<SafetyPermissionColumnRespVO>> getColumnList(
            @RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName,
            @RequestParam("schemaName") String schemaName,
            @RequestParam("tableReference") String tableReference) {
        return success(permissionService.getColumnList(databaseId, schemaName, tableName, tableReference));
    }

}
