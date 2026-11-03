package cn.iocoder.yudao.module.dataforge.controller.admin.database;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.getFirst;

@Tag(name = "管理后台 - 数据源中心--数据库信息")
@RestController
@RequestMapping("/dataforge/database")
@Validated
public class DatabaseController {

    @Resource
    private DatabaseService databaseService;

    /**
     * 创建数据库信息。
     *
     * @param createReqVO 创建参数
     * @return 数据源
     */
    @PostMapping("/create")
    @Operation(summary = "创建数据库信息")
    @PreAuthorize("@ss.hasPermission('dataforge:database:create')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Long> createDatabase(@Valid @RequestBody DatabaseSaveReqVO createReqVO) {
        return success(databaseService.createDatabase(createReqVO));
    }

    /**
     * 更新数据库信息。
     *
     * @param updateReqVO 更新参数
     * @return 数据源
     */
    @PutMapping("/update")
    @Operation(summary = "更新数据库信息")
    @PreAuthorize("@ss.hasPermission('dataforge:database:update')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Boolean> updateDatabase(@Valid @RequestBody DatabaseSaveReqVO updateReqVO) {
        databaseService.updateDatabase(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据库信息。
     *
     * @param id 记录编号
     * @return 数据源
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除数据库信息")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:database:delete')")
    public CommonResult<Boolean> deleteDatabase(@RequestParam("id") Long id) {
        databaseService.deleteDatabase(id);
        return success(true);
    }

    /**
     * 批量删除数据库信息。
     *
     * @param ids 记录编号集合
     * @return 数据源
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @Operation(summary = "批量删除数据库信息")
    @PreAuthorize("@ss.hasPermission('dataforge:database:delete')")
    public CommonResult<Boolean> deleteDatabaseList(@RequestParam("ids") List<Long> ids) {
        databaseService.deleteDatabaseListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据库信息。
     *
     * @param id 记录编号
     * @return 数据源响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据库信息")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<DatabaseRespVO> getDatabase(@RequestParam("id") Long id) {
        DatabaseDO database = databaseService.getDatabase(id);
        List<DatabaseRespVO> list = databaseService.convertToRespList(
                database == null ? Collections.emptyList() : Collections.singletonList(database));
        DatabaseRespVO respVO = getFirst(list);
        return success(respVO);
    }

    /**
     * 测试数据库连接。
     *
     * @param id 记录编号
     * @return 数据源
     */
    @GetMapping("/test")
    @Operation(summary = "测试数据库连接")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<Boolean> testDatabase(@RequestParam("id") Long id) {
        databaseService.testDatabase(id);
        return success(true);
    }

    /**
     * 按表单内容测试数据库连接。
     *
     * @param reqVO 请求参数
     * @return 数据源
     */
    @PostMapping("/test-connection")
    @Operation(summary = "按表单内容测试数据库连接")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<Boolean> testDatabaseConnection(@RequestBody DatabaseSaveReqVO reqVO) {
        databaseService.testDatabase(reqVO);
        return success(true);
    }

    /**
     * 分组诊断数据库连接。
     *
     * @param reqVO 请求参数
     * @return 数据源诊断响应数据
     */
    @PostMapping("/diagnose")
    @Operation(summary = "分组诊断数据库连接", description = "默认只读；写入检查需额外更新权限且仅使用独立临时表")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query') and (!#reqVO.includeWriteChecks or @ss.hasPermission('dataforge:database:update'))")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<DatabaseDiagnosisRespVO> diagnoseDatabase(@Valid @RequestBody DatabaseDiagnosisReqVO reqVO) {
        return success(databaseService.diagnoseDatabase(reqVO));
    }

    /**
     * 获得数据库信息分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据库信息分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据库信息分页")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<PageResult<DatabaseRespVO>> getDatabasePage(@Valid DatabasePageReqVO pageReqVO) {
        PageResult<DatabaseDO> pageResult = databaseService.getDatabasePage(pageReqVO);
        return success(new PageResult<>(
                databaseService.convertToRespList(pageResult.getList()), pageResult.getTotal()));
    }

    /**
     * 获得数据源精简列表。
     *
     * @param usageType 用途类型
     * @return 数据源精简列表结果集合
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得数据源精简列表", description = "用于数据同步等页面的下拉选择，可按库用途过滤")
    @Parameter(name = "usageType", description = "库用途：1-业务库 2-中台库", example = "2")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<List<DatabaseSimpleRespVO>> getSimpleDatabaseList(
            @RequestParam(value = "usageType", required = false) Integer usageType) {
        return success(databaseService.getSimpleDatabaseList(usageType));
    }

    /**
     * 获得数据源下的表清单。
     *
     * @param id 记录编号
     * @return 数据源下的表清单结果集合
     */
    @GetMapping("/table-list")
    @Operation(summary = "获得数据源下的表清单")
    @Parameter(name = "id", description = "数据源编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<List<DatabaseTableRespVO>> getTableList(@RequestParam("id") Long id) {
        return success(databaseService.getTableList(id));
    }

    /**
     * 获得数据源下指定表的属性与字段结构。
     *
     * @param id 记录编号
     * @param tableName 表名
     * @return 数据源表元数据响应数据
     */
    @GetMapping("/table-meta")
    @Operation(summary = "获得数据源下指定表的属性与字段结构")
    @PreAuthorize("@ss.hasPermission('dataforge:database:query')")
    public CommonResult<DatabaseTableMetaRespVO> getTableMeta(
            @RequestParam("id") Long id,
            @RequestParam("tableName") String tableName) {
        return success(databaseService.getTableMeta(id, tableName));
    }

    /**
     * 只读预览数据源表样例数据（最多 100 行）。
     *
     * @param reqVO 请求参数
     * @return 数据源预览响应数据
     */
    @PostMapping("/preview-table")
    @Operation(summary = "只读预览数据源表样例数据（最多 100 行）")
    @PreAuthorize("@ss.hasPermission('dataforge:database:preview')")
    public CommonResult<DatabasePreviewRespVO> previewTable(
            @Valid @RequestBody DatabasePreviewReqVO reqVO) {
        return success(databaseService.previewTable(reqVO));
    }

    /**
     * 导出数据库信息 Excel。
     *
     * @param pageReqVO 分页查询条件
     * @param response 响应对象
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据库信息 Excel")
    @PreAuthorize("@ss.hasPermission('dataforge:database:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportDatabaseExcel(@Valid DatabasePageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DatabaseDO> list = databaseService.getDatabasePage(pageReqVO).getList();
        ExcelUtils.write(response, "数据源中心--数据库信息.xls", "数据", DatabaseRespVO.class,
                databaseService.convertToRespList(list));
    }

}
