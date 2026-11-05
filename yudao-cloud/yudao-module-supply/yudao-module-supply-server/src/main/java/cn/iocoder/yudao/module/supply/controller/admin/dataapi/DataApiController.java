package cn.iocoder.yudao.module.supply.controller.admin.dataapi;

import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.*;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
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

@Tag(name = "管理后台 - 数据服务-API 管理")
@RestController
@RequestMapping("/supply/data-api")
@Validated
public class DataApiController {

    @Resource
    private DataApiService dataApiService;

    /**
     * 创建 API 服务。
     *
     * @param createReqVO 创建参数
     * @return 数据 API
     */
    @LogRecord(type = "数据服务-API 管理", subType = "创建 API 服务", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了API服务【{{#createReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建 API 服务")
    @PreAuthorize("@ss.hasPermission('supply:data-api:create')")
    public CommonResult<Long> createDataApi(@Valid @RequestBody DataApiSaveReqVO createReqVO) {
        return success(dataApiService.createDataApi(createReqVO));
    }

    /**
     * 更新 API 服务。
     *
     * @param updateReqVO 更新参数
     * @return 数据 API
     */
    @LogRecord(type = "数据服务-API 管理", subType = "更新 API 服务", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了API服务【{{#auditObjectName}}】，名称由【{{#auditObjectName}}】改为【{{#updateReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "更新 API 服务")
    @PreAuthorize("@ss.hasPermission('supply:data-api:update')")
    public CommonResult<Boolean> updateDataApi(@Valid @RequestBody DataApiSaveReqVO updateReqVO) {
        var auditBefore = dataApiService.getDataApiEntity(updateReqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + updateReqVO.getId() : auditBefore.getName());

        dataApiService.updateDataApi(updateReqVO);
        return success(true);
    }

    /**
     * 上线/下线 API 服务。
     *
     * @param reqVO 请求参数
     * @return 数据 API
     */
    @LogRecord(type = "数据服务-API 管理", subType = "上线/下线 API 服务", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "将API服务【{{#auditObjectName}}】状态设为【{{#reqVO.status == 1 ? '上线' : '下线'}}】，原控制版本【{{#reqVO.expectedRowVersion}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-status")
    @Operation(summary = "上线/下线 API 服务")
    @PreAuthorize("@ss.hasPermission('supply:data-api:publish')")
    public CommonResult<Boolean> updateDataApiStatus(@Valid @RequestBody DataApiUpdateStatusReqVO reqVO) {
        var auditBefore = dataApiService.getDataApiEntity(reqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + reqVO.getId() : auditBefore.getName());

        dataApiService.updateDataApiStatus(reqVO.getId(), reqVO.getStatus(), reqVO.getExpectedRowVersion());
        return success(true);
    }

    /**
     * 删除 API 服务。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    @LogRecord(type = "数据服务-API 管理", subType = "删除 API 服务", bizNo = "{{(#id) ?: 0}}",
            success = "删除了API服务【{{#auditObjectName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除 API 服务")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:data-api:delete')")
    public CommonResult<Boolean> deleteDataApi(@RequestParam("id") Long id) {
        var auditBefore = dataApiService.getDataApiEntity(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        dataApiService.deleteDataApi(id);
        return success(true);
    }

    /**
     * 获得 API 服务。
     *
     * @param id 记录编号
     * @return 数据 API响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得 API 服务")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('supply:data-api:query')")
    public CommonResult<DataApiRespVO> getDataApi(@RequestParam("id") Long id) {
        return success(dataApiService.getDataApi(id));
    }

    /**
     * 获得 API 服务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return  API 服务分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得 API 服务分页")
    @PreAuthorize("@ss.hasPermission('supply:data-api:query')")
    public CommonResult<PageResult<DataApiRespVO>> getDataApiPage(@Valid DataApiPageReqVO pageReqVO) {
        return success(dataApiService.getDataApiPage(pageReqVO));
    }

    /**
     * 获得 API 服务精简列表（接口测试/应用授权下拉用）。
     *
     * @param status 目标状态
     * @param purpose 用途
     * @return  API 服务精简列表（接口测试/应用授权下拉用）结果集合
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得 API 服务精简列表（接口测试/应用授权下拉用）")
    @Parameter(name = "status", description = "状态过滤（可空）")
    @Parameter(name = "purpose", description = "用途：query/test/grant")
    @PreAuthorize("@ss.hasAnyPermissions('supply:data-api:query', 'supply:data-api:test', 'supply:data-api:invoke', 'supply:app:grant', 'supply:app:update')")
    public CommonResult<List<DataApiSimpleRespVO>> getDataApiSimpleList(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "purpose", required = false) String purpose) {
        return success(dataApiService.getDataApiSimpleList(status, purpose));
    }

    // ==================== 向导辅助 ====================

    /**
     * 获得数据源精简列表（向导下拉用）。
     *
     * @return 数据源列表
     */
    @GetMapping("/database-list")
    @Operation(summary = "获得数据源精简列表（向导下拉用）")
    @PreAuthorize("@ss.hasPermission('supply:data-api:query')")
    public CommonResult<List<SupplyDatabaseSimpleRespVO>> getDatabaseList() {
        return success(dataApiService.getDatabaseList());
    }

    /**
     * 获得对外网关基址（未配置时为空，不得猜测）。
     *
     * @return 公开基础URL
     */
    @GetMapping("/public-base-url")
    @Operation(summary = "获得对外网关基址（未配置时为空，不得猜测）")
    @PreAuthorize("@ss.hasAnyPermissions('supply:data-api:query', 'supply:data-api:test', 'supply:data-api:invoke', 'supply:app:query')")
    public CommonResult<String> getPublicBaseUrl() {
        return success(dataApiService.getPublicBaseUrl());
    }

    /**
     * 获得数据源下的表名列表（向导下拉用）。
     *
     * @param databaseId 数据源编号
     * @return 数据源下的表名列表（向导下拉用）结果集合
     */
    @GetMapping("/table-list")
    @Operation(summary = "获得数据源下的表名列表（向导下拉用）")
    @Parameter(name = "databaseId", description = "数据源编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:data-api:query')")
    public CommonResult<List<String>> getTableNameList(@RequestParam("databaseId") Long databaseId) {
        return success(dataApiService.getTableNameList(databaseId));
    }

    /**
     * 获得数据表列元数据（向导选择请求参数/返回字段用）。
     *
     * @param databaseId 数据源编号
     * @param tableName 表名
     * @return 数据表列元数据（向导选择请求参数/返回字段用）结果集合
     */
    @GetMapping("/table-columns")
    @Operation(summary = "获得数据表列元数据（向导选择请求参数/返回字段用）")
    @Parameter(name = "databaseId", description = "数据源编号", required = true)
    @Parameter(name = "tableName", description = "表名", required = true)
    @PreAuthorize("@ss.hasPermission('supply:data-api:query')")
    public CommonResult<List<TableColumnRespVO>> getTableColumns(
            @RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName) {
        return success(dataApiService.getTableColumns(databaseId, tableName));
    }

    // ==================== 执行 ====================

    /**
     * 草稿只读预览（配置可未保存，拒绝写入和转发）。
     *
     * @param testReqVO 测试请求参数
     * @return 数据 API执行响应数据
     */
    @PostMapping("/test")
    @Operation(summary = "草稿只读预览（配置可未保存，拒绝写入和转发）")
    @PreAuthorize("@ss.hasPermission('supply:data-api:test')")
    public CommonResult<DataApiExecuteRespVO> testDataApi(@Valid @RequestBody DataApiTestReqVO testReqVO) {
        return success(dataApiService.testDataApi(testReqVO, ServletUtils.getClientIP()));
    }

    /**
     * 在线签名（v1 旧协议或 v2 HMAC-SM3）。
     *
     * @param reqVO 请求参数
     * @return 数据 API
     */
    @PostMapping("/sign")
    @Operation(summary = "在线签名（v1 旧协议或 v2 HMAC-SM3）")
    @ApiAccessLog(requestEnable = false, sanitizeKeys = {"appSecret", "secret"})
    @PreAuthorize("@ss.hasAnyPermissions('supply:data-api:query', 'supply:data-api:test', 'supply:data-api:invoke')")
    public CommonResult<String> signDataApi(@Valid @RequestBody DataApiSignReqVO reqVO) {
        return success(dataApiService.sign(reqVO));
    }

    /**
     * 发布当前草稿为不可变快照。
     *
     * @param reqVO 请求参数
     * @return 数据 API
     */
    @LogRecord(type = "数据服务-API 管理", subType = "发布当前草稿为不可变快照", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "发布了API服务【{{#auditObjectName}}】（编号{{#reqVO.id}}），生成发布快照【{{#_ret.data}}】，发布前控制版本【{{#reqVO.expectedRowVersion}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/publish")
    @Operation(summary = "发布当前草稿为不可变快照")
    @PreAuthorize("@ss.hasPermission('supply:data-api:publish')")
    public CommonResult<Long> publishDataApi(@Valid @RequestBody DataApiPublishReqVO reqVO) {
        var auditBefore = dataApiService.getDataApiEntity(reqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + reqVO.getId() : auditBefore.getName());

        return success(dataApiService.publishDataApi(reqVO.getId(), reqVO.getExpectedRowVersion()));
    }

    /**
     * 回滚到既有发布快照。
     *
     * @param reqVO 请求参数
     * @return 数据 API
     */
    @LogRecord(type = "数据服务-API 管理", subType = "回滚到既有发布快照", bizNo = "{{(#reqVO.id) ?: 0}}",
            success = "将API服务【{{#auditObjectName}}】（编号{{#reqVO.id}}）回滚到发布快照【{{#reqVO.releaseId}}】，原控制版本【{{#reqVO.expectedRowVersion}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/rollback")
    @Operation(summary = "回滚到既有发布快照")
    @PreAuthorize("@ss.hasPermission('supply:data-api:rollback')")
    public CommonResult<Boolean> rollbackDataApi(@Valid @RequestBody DataApiRollbackReqVO reqVO) {
        var auditBefore = dataApiService.getDataApiEntity(reqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + reqVO.getId() : auditBefore.getName());

        dataApiService.rollbackDataApi(reqVO.getId(), reqVO.getReleaseId(), reqVO.getExpectedRowVersion());
        return success(true);
    }

    /**
     * 获得 API 发布历史。
     *
     * @param apiId API 编号
     * @return  API 发布历史结果集合
     */
    @GetMapping("/release/list")
    @Operation(summary = "获得 API 发布历史")
    @Parameter(name = "apiId", description = "API 编号", required = true)
    @PreAuthorize("@ss.hasPermission('supply:data-api:query')")
    public CommonResult<List<DataApiReleaseRespVO>> listReleases(@RequestParam("apiId") Long apiId) {
        return success(dataApiService.listReleases(apiId));
    }

}
